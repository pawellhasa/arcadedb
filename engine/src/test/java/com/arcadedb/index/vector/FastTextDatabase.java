package com.arcadedb.index.vector;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.graph.Vertex;
import com.arcadedb.log.LogManager;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.utility.FileUtils;
import com.github.jelmerk.knn.DistanceFunctions;
import com.github.jelmerk.knn.SearchResult;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import java.util.stream.*;
import java.util.zip.*;

import static java.util.concurrent.TimeUnit.*;

/**
 * Example application that downloads the english fast-text word vectors, inserts them into an hnsw index and lets
 * you query them.
 */
public class FastTextDatabase {

  private static final String WORDS_FILE_URL = "https://dl.fbaipublicfiles.com/fasttext/vectors-crawl/cc.en.300.vec.gz";

  private static final Path TMP_PATH = Paths.get(System.getProperty("java.io.tmpdir"));

  public static void main(String[] args) throws Exception {
    new FastTextDatabase();
  }

  public FastTextDatabase() throws IOException, InterruptedException, ReflectiveOperationException {
    final long start = System.currentTimeMillis();

    final Database database;
    final HnswVectorIndex persistentIndex;

    final DatabaseFactory factory = new DatabaseFactory("textdb");

    // TODO: REMOVE THIS
//    if (factory.exists())
//      factory.open().drop();

    if (factory.exists()) {
      database = factory.open();
      LogManager.instance().log(this, Level.SEVERE, "Found existent database with %d words", database.countType("Word", false));

      final String indexJsonCfg = FileUtils.readFileAsString(new File("index.json"), "UTF8");

      persistentIndex = new HnswVectorIndex(database, new JSONObject(indexJsonCfg));

    } else {
      database = factory.create();
      LogManager.instance().log(this, Level.SEVERE, "Creating new database");
      final VertexType vType = database.getSchema().createVertexType("Word");
      vType.getOrCreateProperty("name", Type.STRING);

      final Path file = TMP_PATH.resolve("cc.en.300.vec.gz");
      if (!Files.exists(file)) {
        downloadFile(WORDS_FILE_URL, file);
      } else {
        LogManager.instance().log(this, Level.SEVERE, "Input file already downloaded. Using %s", file);
      }

      List<Word> words = loadWordVectors(file);

      final HnswVectorIndexRAM<String, float[], Word, Float> hnswIndex = HnswVectorIndexRAM.newBuilder(300, DistanceFunctions.FLOAT_INNER_PRODUCT, words.size())
          .withM(16).withEf(200).withEfConstruction(200).build();

      hnswIndex.addAll(words, (workDone, max) -> LogManager.instance()
          .log(null, Level.SEVERE, "Added %d out of %d words to the index (elapsed %ds).", workDone, max, (System.currentTimeMillis() - start) / 1000));

      long end = System.currentTimeMillis();
      long duration = end - start;

      LogManager.instance().log(null, Level.SEVERE, "Creating index with %d words took %d millis which is %d minutes.", hnswIndex.size(), duration,
          MILLISECONDS.toMinutes(duration));

      LogManager.instance().log(null, Level.SEVERE, "Saving index into the database...");

      persistentIndex = hnswIndex.createPersistentIndex()//
          .withDatabase(database)//
          .withVertexType("Word").withEdgeType("Proximity").withVectorPropertyName("vector").withIdProperty("name").build();

      FileUtils.writeFile(new File("index.json"), persistentIndex.toJSON().toString());

      LogManager.instance().log(null, Level.SEVERE, "Index saved in %ds.", System.currentTimeMillis() - end);
    }

    try {
      long end = System.currentTimeMillis();
      long duration = end - start;

      LogManager.instance().log(this, Level.SEVERE, "Creating index with took %d millis which is %d minutes.%n", duration, MILLISECONDS.toMinutes(duration));

      //Console console = System.console();

      int k = 10;

      while (true) {
        System.out.println("Enter an english word : ");

        String input = "dog";  // console.readLine();

        final long startWord = System.currentTimeMillis();

        List<SearchResult<Vertex, Float>> approximateResults = persistentIndex.findNeighbors(input, k);

        System.out.printf("Found similar words for '%s' in %dms%n", input, System.currentTimeMillis() - startWord);

//      final long startBruteForce = System.currentTimeMillis();
//
//      List<SearchResult<Word, Float>> groundTruthResults = groundTruthIndex.findNeighbors(input, k);
//
//      System.out.printf("Found similar words using brute force for '%s' in %dms%n", input, System.currentTimeMillis() - startBruteForce);
//
        System.out.printf("Most similar words found using HNSW index : %n%n");
        for (SearchResult<Vertex, Float> result : approximateResults) {
          System.out.printf("%s %.4f%n", result.item().getString("name"), result.distance());
        }
//
//      System.out.printf("%nMost similar words found using exact index: %n%n");
//      for (SearchResult<Word, Float> result : groundTruthResults) {
//        System.out.printf("%s %.4f%n", result.item().id(), result.distance());
//      }
//      int correct = groundTruthResults.stream().mapToInt(r -> approximateResults.contains(r) ? 1 : 0).sum();
//      System.out.printf("%nAccuracy : %.4f%n%n", correct / (double) groundTruthResults.size());

        Thread.sleep(1000);
      }
    } finally {
      database.close();
    }
  }

  private void downloadFile(String url, Path path) throws IOException {
    LogManager.instance().log(this, Level.SEVERE, "Downloading %s to %s. This may take a while", url, path);
    try (InputStream in = new URL(url).openStream()) {
      Files.copy(in, path);
    }
    LogManager.instance().log(this, Level.SEVERE, "Downloaded %s", FileUtils.getSizeAsString(path.toFile().length()));
  }

  private static List<Word> loadWordVectors(Path path) throws IOException {
    LogManager.instance().log(null, Level.SEVERE, "Loading words from %s", path);

    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
      return reader.lines().skip(1)//
          //.limit(10_000)//
          .map(line -> {
            String[] tokens = line.split(" ");

            String word = tokens[0];

            float[] vector = new float[tokens.length - 1];
            for (int i = 1; i < tokens.length - 1; i++) {
              vector[i] = Float.parseFloat(tokens[i]);
            }

            return new Word(word, VectorUtils.normalize(vector)); // normalize the vector so we can do inner product search
          }).collect(Collectors.toList());
    }
  }
}