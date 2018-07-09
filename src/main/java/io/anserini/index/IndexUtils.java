/**
 * Anserini: An information retrieval toolkit built on Lucene
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.anserini.index;

import io.anserini.index.generator.LuceneDocumentGenerator;
import io.anserini.index.generator.TweetGenerator;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.ParserProperties;
import edu.stanford.nlp.simple.Sentence;
import org.jsoup.Jsoup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.anserini.search.SearchCollection.BREAK_SCORE_TIES_BY_DOCID;
import static io.anserini.search.SearchCollection.BREAK_SCORE_TIES_BY_TWEETID;

public class IndexUtils {
  private static final Logger LOG = LogManager.getLogger(IndexUtils.class);

  enum Compression { NONE, GZ, BZ2, ZIP }

  public static final class Args {
    @Option(name = "-index", metaVar = "[Path]", required = true, usage = "index path")
    String index;

    @Option(name = "-stats", usage = "print index statistics")
    boolean stats;

    @Option(name = "-printTermInfo", metaVar = "term", usage = "prints term info (stemmed, total counts, doc counts, etc.)")
    String term;

    @Option(name = "-printDocvector", metaVar = "docid", usage = "prints the document vector of a document")
    String docvectorDocid;

    @Option(name = "-dumpAllDocids", usage = "dumps all docids in sorted order. For non-tweet collection the order is " +
            "in ascending of String docid; For tweets collection the order is in descending of Long tweet id" +
            "please provide the compression scheme for the output")
    Compression dumpAllDocids;

    @Option(name = "-dumpRawDoc", metaVar = "docid", usage = "dumps raw document (if stored in the index)")
    String rawDoc;

    @Option(name = "-dumpRawDocs", metaVar = "[Path]", usage = "dumps raw documents from the input file")
    String rawDocs;

    @Option(name = "-dumpRawDocsWithDocid", metaVar = "[Path]", usage = "By default there is no <DOCNO>docid<DOCNO> " +
            "stored in the raw docs. By prepending <DOCNO>docid<DOCNO> in front of the raw docs we can directly index them")
    String rawDocsWithDocid;

    @Option(name = "-dumpTransformedDoc", metaVar = "docid", usage = "dumps transformed document (if stored in the index)")
    String transformedDoc;

    @Option(name = "-dumpSentences", metaVar = "docid", usage = "splits the fetched document into sentences (if stored in the index)")
    String sentDoc;

    @Option(name = "-convertDocidToLuceneDocid", metaVar = "docid", usage = "converts a collection lookupDocid to a Lucene internal lookupDocid")
    String lookupDocid;

    @Option(name = "-convertLuceneDocidToDocid", metaVar = "docid", usage = "converts to a Lucene internal lookupDocid to a collection lookupDocid ")
    int lookupLuceneDocid;
  }

  public class NotStoredException extends Exception {
    public NotStoredException(String message) {
      super(message);
    }
  }

  private final FSDirectory directory;
  private final DirectoryReader reader;

  public IndexUtils(String indexPath) throws IOException {
    this.directory = FSDirectory.open(new File(indexPath).toPath());
    this.reader = DirectoryReader.open(directory);
  }

  public InputStream getReadFileStream(String path) throws IOException {
    InputStream fin = Files.newInputStream(Paths.get(path), StandardOpenOption.READ);
    BufferedInputStream in = new BufferedInputStream(fin);
    if (path.endsWith(".bz2")) {
      BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(in);
      return bzIn;
    } else if (path.endsWith(".gz")) {
      GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in);
      return gzIn;
    } else if (path.endsWith(".zip")) {
      GzipCompressorInputStream zipIn = new GzipCompressorInputStream(in);
      return zipIn;
    }
    return in;
  }

  void printIndexStats() throws IOException {
    Fields fields = MultiFields.getFields(reader);
    Terms terms = fields.terms(LuceneDocumentGenerator.FIELD_BODY);

    System.out.println("Index statistics");
    System.out.println("----------------");
    System.out.println("documents:             " + reader.numDocs());
    System.out.println("documents (non-empty): " + reader.getDocCount(LuceneDocumentGenerator.FIELD_BODY));
    System.out.println("unique terms:          " + terms.size());
    System.out.println("total terms:           " + reader.getSumTotalTermFreq(LuceneDocumentGenerator.FIELD_BODY));

    System.out.println("stored fields:");

    FieldInfos fieldInfos = MultiFields.getMergedFieldInfos(reader);
    for (String fd : fields) {
      FieldInfo fi = fieldInfos.fieldInfo(fd);
      System.out.println("  " + fd + " (" + "indexOption: " + fi.getIndexOptions() +
          ", hasVectors: " + fi.hasVectors() + ")");
    }
  }

  public void printTermCounts(String termStr) throws IOException, ParseException {
    EnglishAnalyzer ea = new EnglishAnalyzer(CharArraySet.EMPTY_SET);
    QueryParser qp = new QueryParser(LuceneDocumentGenerator.FIELD_BODY, ea);
    TermQuery q = (TermQuery)qp.parse(termStr);
    Term t = q.getTerm();

    System.out.println("raw term:             " + termStr);
    System.out.println("stemmed term:         " + q.toString(LuceneDocumentGenerator.FIELD_BODY));
    System.out.println("collection frequency: " + reader.totalTermFreq(t));
    System.out.println("document frequency:   " + reader.docFreq(t));

    PostingsEnum postingsEnum = MultiFields.getTermDocsEnum(reader, LuceneDocumentGenerator.FIELD_BODY, t.bytes());
    System.out.println("postings:\n");
    while (postingsEnum.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
      System.out.printf("\t%s, %s\n", postingsEnum.docID(), postingsEnum.freq());
    }
  }

  public void printDocumentVector(String docid) throws IOException, NotStoredException {
    Terms terms = reader.getTermVector(convertDocidToLuceneDocid(docid),
        LuceneDocumentGenerator.FIELD_BODY);
    if (terms == null) {
      throw new NotStoredException("Document vector not stored!");
    }
    TermsEnum te = terms.iterator();
    if (te == null) {
      throw new NotStoredException("Document vector not stored!");
    }
    while ((te.next()) != null) {
      System.out.println(te.term().utf8ToString() + " " + te.totalTermFreq());
    }
  }

  public void getAllDocids(Compression compression) throws IOException {
    Query q = new FieldValueQuery(LuceneDocumentGenerator.FIELD_ID);
    IndexSearcher searcher = new IndexSearcher(reader);
    ScoreDoc[] scoreDocs;
    try {
      scoreDocs = searcher.search(new FieldValueQuery(LuceneDocumentGenerator.FIELD_ID), reader.maxDoc(),
          BREAK_SCORE_TIES_BY_DOCID).scoreDocs;
    } catch (IllegalStateException e) { // because this is tweets collection
      scoreDocs = searcher.search(new FieldValueQuery(TweetGenerator.StatusField.ID_LONG.name), reader.maxDoc(),
          BREAK_SCORE_TIES_BY_TWEETID).scoreDocs;
    }

    String basePath = directory.getDirectory().getFileName().toString() + ".allDocids";
    OutputStream outStream = null;
    String outputPath = "";
    switch (compression) {
      case NONE:
        outputPath = basePath+".txt";
        outStream = Files.newOutputStream(Paths.get(outputPath));
        break;
      case GZ:
        outputPath = basePath+".gz";
        outStream = new GzipCompressorOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(outputPath))));
        break;
      case ZIP:
        outputPath = basePath+".zip";
        outStream = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(outputPath))));
        ((ZipOutputStream) outStream).putNextEntry(new ZipEntry(basePath));
        break;
      case BZ2:
        outputPath = basePath+".bz2";
        outStream = new BZip2CompressorOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(outputPath))));
        break;
    }
    for (int i = 0; i < scoreDocs.length; i++) {
      StringBuilder builder = new StringBuilder();
      builder.append(searcher.doc(scoreDocs[i].doc).getField(LuceneDocumentGenerator.FIELD_ID).stringValue()).append("\n");
      outStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
    }
    outStream.close();
    System.out.println(String.format("All Documents IDs are output to: %s", outputPath));
  }

  public String getRawDocument(String docid) throws IOException, NotStoredException {
    Document d = reader.document(convertDocidToLuceneDocid(docid));
    IndexableField doc = d.getField(LuceneDocumentGenerator.FIELD_RAW);
    if (doc == null) {
      throw new NotStoredException("Raw documents not stored!");
    }
    return doc.stringValue();
  }

  public void dumpRawDocuments(String reqDocidsPath, boolean prependDocid) throws IOException, NotStoredException {
    InputStream in = getReadFileStream(reqDocidsPath);
    BufferedReader bRdr = new BufferedReader(new InputStreamReader(in));
    FileOutputStream fOut = new FileOutputStream(new File(reqDocidsPath+".output.tar.gz"));
    BufferedOutputStream bOut = new BufferedOutputStream(fOut);
    GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(bOut);
    TarArchiveOutputStream tOut = new TarArchiveOutputStream(gzOut);

    String docid;
    while ((docid = bRdr.readLine()) != null) {
      Document d = reader.document(convertDocidToLuceneDocid(docid));
      IndexableField doc = d.getField(LuceneDocumentGenerator.FIELD_RAW);
      if (doc == null) {
        throw new NotStoredException("Raw documents not stored!");
      }
      TarArchiveEntry tarEntry = new TarArchiveEntry(new File(docid));
      tarEntry.setSize(doc.stringValue().length() + (prependDocid ? String.format("<DOCNO>%s</DOCNO>\n", docid).length() : 0));
      tOut.putArchiveEntry(tarEntry);
      if (prependDocid) {
        tOut.write(String.format("<DOCNO>%s</DOCNO>\n", docid).getBytes());
      }
      tOut.write(doc.stringValue().getBytes(StandardCharsets.UTF_8));
      tOut.closeArchiveEntry();
    }
    tOut.close();
    System.out.println(String.format("Raw documents are output to: %s", reqDocidsPath+".output.tar.gz"));
  }

  public String getTransformedDocument(String docid) throws IOException, NotStoredException {
    Document d = reader.document(convertDocidToLuceneDocid(docid));
    IndexableField doc = d.getField(LuceneDocumentGenerator.FIELD_BODY);
    if (doc == null) {
      throw new NotStoredException("Transformed documents not stored!");
    }
    return doc.stringValue();
  }

  public List<Sentence> getSentDocument(String docid) throws IOException, NotStoredException {
    String toSplit;
    try {
      toSplit = getTransformedDocument(docid);
    } catch (NotStoredException e) {
      String rawDoc = getRawDocument(docid);
      org.jsoup.nodes.Document jDoc = Jsoup.parse(rawDoc);
      toSplit = jDoc.text();
    }
    edu.stanford.nlp.simple.Document doc = new edu.stanford.nlp.simple.Document(toSplit);
    return doc.sentences();
  }

  public int convertDocidToLuceneDocid(String docid) throws IOException {
    IndexSearcher searcher = new IndexSearcher(reader);

    Query q = new TermQuery(new Term(LuceneDocumentGenerator.FIELD_ID, docid));
    TopDocs rs = searcher.search(q, 1);
    ScoreDoc[] hits = rs.scoreDocs;

    if (hits == null) {
      throw new RuntimeException("Docid not found!");
    }

    return hits[0].doc;
  }

  public String convertLuceneDocidToDocid(int docid) throws IOException {
    Document d = reader.document(docid);
    IndexableField doc = d.getField(LuceneDocumentGenerator.FIELD_ID);
    if (doc == null) {
      // Really shouldn't happen!
      throw new RuntimeException();
    }
    return doc.stringValue();
  }

  public static void main(String[] argv) throws Exception{
    Args args = new Args();
    CmdLineParser parser = new CmdLineParser(args, ParserProperties.defaults().withUsageWidth(90));
    try {
      parser.parseArgument(argv);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      return;
    }

    final IndexUtils util = new IndexUtils(args.index);

    if (args.stats) {
      util.printIndexStats();
    }

    if (args.term != null) {
      util.printTermCounts(args.term);
    }

    if (args.docvectorDocid != null) {
      util.printDocumentVector(args.docvectorDocid);
    }

    if (args.dumpAllDocids != null) {
      util.getAllDocids(args.dumpAllDocids);
    }

    if (args.rawDoc != null) {
      System.out.println(util.getRawDocument(args.rawDoc));
    }

    if (args.rawDocs != null) {
      util.dumpRawDocuments(args.rawDocs, false);
    }

    if (args.rawDocsWithDocid != null) {
      util.dumpRawDocuments(args.rawDocs, true);
    }

    if (args.transformedDoc != null) {
      System.out.println(util.getTransformedDocument(args.transformedDoc));
    }

    if (args.sentDoc != null) {
      for (Sentence sent: util.getSentDocument(args.sentDoc)){
        System.out.println(sent);
      }
    }

    if (args.lookupDocid != null) {
      System.out.println(util.convertDocidToLuceneDocid(args.lookupDocid));
    }

    if (args.lookupLuceneDocid > 0) {
      System.out.println(util.convertLuceneDocidToDocid(args.lookupLuceneDocid));
    }
  }
}