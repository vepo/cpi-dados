package br.com.povo.cpi.dados;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.stream.Stream;

import org.apache.lucene.analysis.br.BrazilianAnalyzer;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.FSDirectory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class DadosCpi {
    private static final BrazilianAnalyzer ANALYZER = new BrazilianAnalyzer();
    private static final Path biblioteca = Paths.get(System.getProperty("user.home"), ".cpi-senado");
    private static final Path luceneIndexPath = Paths.get(System.getProperty("user.home"), ".cpi-senado-index");

    private static final HttpClient client = HttpClient.newHttpClient();

    static {
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
    }

    private static void download(Element element, URL docUrl)
            throws MalformedURLException, URISyntaxException, IOException, FileNotFoundException, InterruptedException {
        var paths = docUrl.getPath().split("/");
        var docData = biblioteca.resolve(paths[paths.length - 1] + ".pdf");
        var docInfo = biblioteca.resolve(paths[paths.length - 1] + ".info");
        if (!docData.toFile().exists()) {
            System.out.println("Executing request: " + docUrl);

            var request = HttpRequest.newBuilder().uri(docUrl.toURI()).build();
            var response = client.send(request, BodyHandlers.ofInputStream());
            if (response.statusCode() == 200) {
                try (var writer = new FileOutputStream(docData.toFile());
                        var infoWriter = new BufferedWriter(new FileWriter(docInfo.toFile()))) {
                    infoWriter.write(element.text());
                    writer.write(response.body().readAllBytes());
                }
                System.out.println("Arquivo salvo: " + element.text());
            }
        }
    }

    public static void indexer(Path indexDirectoryPath) throws IOException {
        try (var writer = new IndexWriter(FSDirectory.open(indexDirectoryPath),
                new IndexWriterConfig(ANALYZER).setCommitOnClose(true))) {
            Stream.of(biblioteca.toFile().list()).filter(file -> file.endsWith(".pdf"))
                    .filter(file -> !biblioteca.resolve(file.replace(".pdf", ".index")).toFile().exists())
                    .forEach(file -> {
                        try (var markerWriter = new BufferedWriter(
                                new FileWriter(biblioteca.resolve(file.replace(".pdf", ".index")).toFile()))) {
                            var doc = PDFDocument.create(biblioteca.resolve(file).toFile());

                            doc.add(new StringField("filename",
                                    Files.readString(biblioteca.resolve(file.replace(".pdf", ".info"))), Store.YES));
                            writer.addDocument(doc);
                            markerWriter.append("done");
                            System.out.println("Arquivo indexado: "
                                    + Files.readString(biblioteca.resolve(file.replace(".pdf", ".info"))));
                        } catch (IOException e) {
                            try {
                                System.err.println("Não foi possível indexar: "
                                        + Files.readString(biblioteca.resolve(file.replace(".pdf", ".info"))));
                            } catch (IOException e1) {
                                // OK! Vamos ignorar
                            }
                        }
                    });
        }
    }

    public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException {

        if (!biblioteca.toFile().exists()) {
            biblioteca.toFile().mkdirs();
        }

        if (!luceneIndexPath.toFile().exists()) {
            luceneIndexPath.toFile().mkdirs();
        }

        Document doc = Jsoup.connect("https://legis.senado.leg.br/comissoes/docsRecCPI?codcol=2441").get();
        System.out.println(doc.title());
        for (var element : doc.getElementsByTag("a")) {
            if (element.hasAttr("href") && element.attr("href").contains("sdleg-getter")
                    && element.text().startsWith("DOC ")) {
                var docUrl = new URL(element.attr("href"));
                download(element, docUrl);
            }

        }

        indexer(luceneIndexPath);

        realizaBusca();
        System.out.println("Busca encerada!");

    }

    private static void realizaBusca() throws IOException {
        var indexSearcher = new IndexSearcher(FilterDirectoryReader.open(FSDirectory.open(luceneIndexPath)));
        Query ultimaQuery = null;
        ScoreDoc ultimoScore = null;
        int offset = 0;
        try (var scanner = new Scanner(System.in)) {
            String input = "";
            while (!"sair".equalsIgnoreCase(input)) {
                if (null != ultimaQuery && null != ultimoScore) {
                    System.out.println(
                            "Insira o termo de busca, \"mais\" para exibir mais resultados da última busca, ou \"sair\" para finalizar. Para encerrar a busca \"CTRL + C\"");
                } else {
                    System.out.println(
                            "Insira o termo de busca, ou \"sair\" para finalizar. Para encerrar a busca \"CTRL + C\"");
                }
                input = scanner.nextLine();
                var termo = input;
                if (null != ultimaQuery && null != ultimoScore && "mais".equalsIgnoreCase(termo)) {
                    var docs = indexSearcher.searchAfter(ultimoScore, ultimaQuery, 50);
                    if (docs.scoreDocs.length > 0l) {
                        System.out.println("Termo encontrado em arquivos " + (offset + docs.scoreDocs.length) + "/"
                                + docs.totalHits.value);
                        for (ScoreDoc score : docs.scoreDocs) {
                            System.out.printf("%f - %s\n", score.score,
                                    indexSearcher.doc(score.doc).getField("filename").stringValue());
                        }
                        ultimoScore = docs.scoreDocs[docs.scoreDocs.length - 1];
                        offset += docs.scoreDocs.length;
                    } else {
                        System.out.println("Termo não encontrado!");
                        ultimoScore = null;
                    }
                } else {
                    var queries = new BooleanQuery.Builder();
                    Stream.of(termo.split(" ")).filter(s -> !s.isBlank()).forEachOrdered(
                            palavra -> queries.add(new FuzzyQuery(new Term("contents", palavra)), Occur.MUST));
                    var docs = indexSearcher.search(ultimaQuery = queries.build(), 50);
                    offset = 0;
                    if (docs.totalHits.value > 0l) {
                        System.out.println(
                                "Termo encontrado em arquivos " + docs.scoreDocs.length + "/" + docs.totalHits.value);
                        for (ScoreDoc score : docs.scoreDocs) {
                            System.out.printf("%f - %s\n", score.score,
                                    indexSearcher.doc(score.doc).getField("filename").stringValue());
                        }
                        ultimoScore = docs.scoreDocs[docs.scoreDocs.length - 1];
                        offset += docs.scoreDocs.length;
                    } else {
                        System.out.println("Termo não encontrado!");
                        ultimoScore = null;
                    }
                }
            }
        }
    }

}
