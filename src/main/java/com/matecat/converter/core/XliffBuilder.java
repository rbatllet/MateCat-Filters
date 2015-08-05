package com.matecat.converter.core;

import com.matecat.converter.core.format.Format;
import com.matecat.converter.core.okapiclient.OkapiPack;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.Base64;

/**
 * Xliff builder
 *
 * This class is used to build a new XLIFF based on the original XLIFF, containing both the original file and the
 * manifest. The new Xliff follows the
 * <a href="http://docs.oasis-open.org/xliff/v1.2/os/xliff-core.html">v1.2 specification</a>.
 *
 * Both files are inserted creating two new 'file' elements at the beggining of the XLF:
 * 1. Original file
 * 2. Manifest
 *
 * The files are stored as following:
 *
 * <file
 *  original="{ORIGINAL FILENAME (before conversions)}"
 *  datatype="x-{FORMAT (after conversions)}"
 *  source-language="{SRC LANGUAGE}"
 *  target-language="{TARGET LANGUAGE}"
 *  tool-id="matecat-converter">
 *      <header>
 *          <reference>
 *              <internal-file form="base64">{ENCODED CONTENTS OF THE FILE}</internal-file>
 *          </reference>
 *      </header>
 *      <body></body>
 * </file>
 */
class XliffBuilder {

    /**
     * Build the XLIFF, manifest and original file into a new Xliff
     * @param pack Pack generated by Okapi
     * @return New XLIFF generated combining the inputs
     */
    public static File build(final OkapiPack pack) {
        return build(pack, null);
    }


    /**
     * Build the XLIFF, manifest and original file into a new Xliff
     * @param pack Pack generated by Okapi
     * @param originalFormat Original format, if the file was converted before processing it
     * @return New XLIFF generated combining the inputs
     */
    public static File build(final OkapiPack pack, Format originalFormat) {

        // Check the inputs that are not empty
        if (pack == null)
            throw new IllegalArgumentException("The pack cannot be null");

        // Obtain the original format if it's null
        if (originalFormat == null)
            originalFormat = Format.getFormat(pack.getOriginalFile());

        // Retrieve the filename
        String filename = pack.getOriginalFile().getName();

        // Encode the files we are going to insert into the xlf
        String encodedManifest = encodeFile(pack.getManifest());
        String encodedFile = encodeFile(pack.getOriginalFile());

        // Insert the filename, the encoded manifest and the encoded file into the xlf
        File xlf = pack.getXlf();
        String outputPath = pack.getPackFolder().getParentFile().getPath() + File.separator + filename + ".xlf";
        return createXliff(outputPath, xlf, filename, originalFormat, encodedFile, encodedManifest);

    }


    /**
     * Encode the file
     * @param input File to be encoded
     * @return Encoded file
     */
    private static String encodeFile(File input) {
        String output;
        try {
            byte[] bytes = Files.readAllBytes(input.toPath());
            output = Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            //e.printStackTrace();
            throw new RuntimeException("It was not possible to encode the file " + input.getName());
        }
        return output;
    }


    /**
     * Create a new Xliff
     * @param outputPath Path where the new Xliff should be saved
     * @param baseXLF Base xliff
     * @param filename Original file's filename
     * @param originalFormat Original file's format, before any conversion
     * @param encodedFile Encoded original file's contents
     * @param encodedManifest Encoded manifest   @return Xliff generated
     */
    private static File createXliff(String outputPath, final File baseXLF, String filename, Format originalFormat, String encodedFile, String encodedManifest) {

        File output = null;

        try {

            // Parse the XML document
            String xlfContent = FileUtils.readFileToString(baseXLF, "UTF-8").replaceAll("[\\n\\t]","");
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            Document document = documentBuilder.parse(new InputSource(new StringReader(xlfContent)));
            Element root = document.getDocumentElement();

            // Retrieve the source and target language
            Element sampleFile = (Element) document.getElementsByTagName("file").item(0);
            String sourceLanguage = sampleFile.getAttribute("source-language");
            String targetLanguage = sampleFile.getAttribute("target-language");

            // Add the original file
            Element manifestNode = createFileElement(document, sourceLanguage, targetLanguage,
                    "manifest.rkm", null, encodedManifest);
            root.insertBefore(manifestNode, root.getFirstChild());

            // Add the original file
            Element originalFileNode = createFileElement(document, sourceLanguage, targetLanguage,
                    filename, originalFormat, encodedFile);
            root.insertBefore(originalFileNode, root.getFirstChild());

            // Normalize document
            document.normalizeDocument();

            // Save the file
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            DOMSource domSource = new DOMSource(document);
            StreamResult streamResult = new StreamResult(outputPath);
            transformer.transform(domSource, streamResult);

            // Instantiate the output file
            output = new File(outputPath);

            // Check that the file has been correctly created
            if (!output.exists()) {
                throw new RuntimeException("The output Xliff could not been created");
            }

        } catch (ParserConfigurationException | TransformerException | IOException | SAXException pce) {
            pce.printStackTrace();
        }

        // Return the outputted file
        return output;

    }


    /**
     * Create a file element which contains a encoded file
     * @param document XML's document
     * @param sourceLanguage Source language
     * @param targetLanguage Target language
     * @param filename Filename of the file we are storing
     * @param originalFormat Original file's format, before any conversion
     * @param encodedFile Encoded contents of the file we are storing  @return New file element
     */
    private static Element createFileElement(Document document, String sourceLanguage, String targetLanguage,
                                             String filename, Format originalFormat, String encodedFile) {

        // Process filename and original format
        Format format = Format.getFormat(filename);
        if (originalFormat != null  &&  originalFormat != format)  {
            String basename = FilenameUtils.getBaseName(filename);
            filename = String.format("%s.%s", basename,originalFormat);
        }

        // Create the new file element which will contain the original file
        Element originalFileNode = document.createElement("file");
        originalFileNode.setAttribute("tool-id", "matecat-converter");
        originalFileNode.setAttribute("original", filename);
        originalFileNode.setAttribute("datatype", "x-" + format);
        originalFileNode.setAttribute("source-language", sourceLanguage);
        originalFileNode.setAttribute("target-language", targetLanguage);

        // Header
        Element headerElement = document.createElement("header");
        Element referenceElement = document.createElement("reference");
        Element internalFileElement = document.createElement("internal-file");
        internalFileElement.setAttribute("form", "base64");
        internalFileElement.appendChild(document.createTextNode(encodedFile));
        referenceElement.appendChild(internalFileElement);
        headerElement.appendChild(referenceElement);

        // Add the skeleton to the file, and the file to the document
        originalFileNode.appendChild(headerElement);

        // Add empty body
        Element bodyElement = document.createElement("body");
        originalFileNode.appendChild(bodyElement);

        // Return the new node
        return originalFileNode;

    }

}
