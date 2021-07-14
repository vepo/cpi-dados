package br.com.povo.cpi.dados;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Iterator;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexableField;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;

public class PDFDocument implements Iterable<IndexableField> {
    private static final char FILE_SEPARATOR = System.getProperty("file.separator").charAt(0);

    private Document doc;

    private PDFDocument(File documentFile) throws IOException {
        doc = new Document();
        addUnindexedField(doc, "path", documentFile.getPath());
        addUnindexedField(doc, "url", documentFile.getPath().replace(FILE_SEPARATOR, '/'));
        addContent(doc, documentFile);

    }

    private static void addUnindexedField(Document document, String name, String value) {
        if (value != null) {
            document.add(new StringField(name, value, Field.Store.YES));
        }
    }

    private void addTextField(Document document, String name, Reader value) throws IOException {
        if (value != null) {
            document.add(new TextField(name, reader2String(value), Store.YES));
        }
    }

    private String reader2String(Reader value) throws IOException {
        var builder = new StringBuilder();
        var buffer = new char[1024];
        int read = 0;
        while ((read = value.read(buffer)) > 0) {
            builder.append(buffer, 0, read);
        }
        return builder.toString();
    }

    private void addTextField(Document document, String name, String value) {
        if (value != null) {
            document.add(new Field(name, value, TextField.TYPE_STORED));
        }
    }

    private void addContent(Document document, File documentFile) throws IOException {
        PDDocument pdfDocument = null;
        try {
            pdfDocument = PDDocument.load(documentFile);

            // create a writer where to append the text content.
            StringWriter writer = new StringWriter();
            PDFTextStripper stripper = new PDFTextStripper();

            stripper.writeText(pdfDocument, writer);

            String contents = writer.getBuffer().toString();

            StringReader reader = new StringReader(contents);

            // Add the tag-stripped contents as a Reader-valued Text field so it will
            // get tokenized and indexed.
            addTextField(document, "contents", reader);

            PDDocumentInformation info = pdfDocument.getDocumentInformation();
            if (info != null) {
                addTextField(document, "Author", info.getAuthor());
                addTextField(document, "Creator", info.getCreator());
                addTextField(document, "Keywords", info.getKeywords());
                addTextField(document, "Producer", info.getProducer());
                addTextField(document, "Subject", info.getSubject());
                addTextField(document, "Title", info.getTitle());
                addTextField(document, "Trapped", info.getTrapped());
            }
            int summarySize = Math.min(contents.length(), 500);
            String summary = contents.substring(0, summarySize);
            // Add the summary as an UnIndexed field, so that it is stored and returned
            // with hit documents for display.
            addUnindexedField(document, "summary", summary);
        } finally {
            if (pdfDocument != null) {
                pdfDocument.close();
            }
        }
    }

    @Override
    public Iterator<IndexableField> iterator() {
        return doc.iterator();
    }

    public static PDFDocument create(File documentFile) throws IOException {
        return new PDFDocument(documentFile);
    }

    public void add(IndexableField field) {
        doc.add(field);
    }

}
