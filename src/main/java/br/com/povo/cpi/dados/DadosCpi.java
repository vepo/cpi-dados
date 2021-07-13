package br.com.povo.cpi.dados;

import java.io.BufferedWriter;
import java.io.File;
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
import java.util.Objects;
import java.util.Scanner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class DadosCpi {
    private static final Path biblioteca = Paths.get(System.getProperty("user.home"), ".cpi-senado");
    private static final Path luceneIndexPath = Paths.get(System.getProperty("user.home"), ".cpi-senado-index");

    private static final HttpClient client = HttpClient.newHttpClient();

    static {
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
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
        for (Element element : doc.getElementsByTag("a")) {
            if (element.hasAttr("href") && element.attr("href").contains("sdleg-getter")
                    && element.text().startsWith("DOC ")) {
                URL docUrl = new URL(element.attr("href"));
                download(element, docUrl);
            }

        }

        try (Scanner scanner = new Scanner(System.in)) {
            String input = "";
            while (!"sair".equalsIgnoreCase(input)) {

                System.out.println("Insira o termo de busca, ou \"sair\" para finalizar. Para encerrar a busca \"CTRL + C\"");
                input = scanner.nextLine();
                var termo = input;
                var arquivos = Stream.of(biblioteca.toFile().list()).filter(path -> path.endsWith(".pdf")).map(path -> {
                    try {
                        File file = biblioteca.resolve(path).toFile();
                        PDDocument document = PDDocument.load(file);
                        PDFTextStripper pdfStripper = new PDFTextStripper();
                        String text = pdfStripper.getText(document);
                        if (text.toLowerCase().contains(termo.toLowerCase())) {
                            return Files.readString(biblioteca.resolve(path.replace(".pdf", ".info")));
                        }
                        document.close();
                        return null;
                    } catch (IOException e) {
                        return null;
                    }
                }).filter(Objects::nonNull).collect(Collectors.toList());
                if (arquivos.isEmpty()) {
                    System.out.println("Termo não encontrado!");
                } else {
                    System.out.println(
                            "Termo encontrado nos arquivos: " + arquivos.stream().collect(Collectors.joining(", ")));
                }
            }
        }
        System.out.println("Busca encerada!");

    }

    private static void download(Element element, URL docUrl)
            throws MalformedURLException, URISyntaxException, IOException, FileNotFoundException, InterruptedException {
        String[] paths = docUrl.getPath().split("/");
        Path docData = biblioteca.resolve(paths[paths.length - 1] + ".pdf");
        Path docInfo = biblioteca.resolve(paths[paths.length - 1] + ".info");
        if (!docData.toFile().exists()) {
            System.out.println("Executing request: " + docUrl);

            HttpRequest request = HttpRequest.newBuilder().uri(docUrl.toURI()).build();
            var response = client.send(request, BodyHandlers.ofInputStream());
            if (response.statusCode() == 200) {
                try (FileOutputStream writer = new FileOutputStream(docData.toFile());
                        BufferedWriter infoWriter = new BufferedWriter(new FileWriter(docInfo.toFile()))) {
                    infoWriter.write(element.text());
                    writer.write(response.body().readAllBytes());
                }
                System.out.println("Arquivo salvo: " + element.text());
            }
        }
    }

}
