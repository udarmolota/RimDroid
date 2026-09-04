package com.rimdroid;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;

public class PrefsXmlTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Document read(File file) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
    }

    @Test public void firstLaunchCreatesPrefsAndParentDirectories() throws Exception {
        File file = new File(tmp.getRoot(), "new-instance/Config/Prefs.xml");
        assertTrue(PrefsXml.updateTextureCompression(file, false));
        Document xml = read(file);
        assertEquals("prefs", xml.getDocumentElement().getTagName());
        assertEquals("False", xml.getElementsByTagName("textureCompression").item(0).getTextContent());
    }

    @Test public void existingPrefsKeepOtherSettings() throws Exception {
        File file = tmp.newFile("Prefs.xml");
        Files.write(file.toPath(), ("<prefs><screenWidth>1280</screenWidth>"
                + "<fullscreen>False</fullscreen><textureCompression>True</textureCompression>"
                + "<language>Russian</language></prefs>").getBytes(StandardCharsets.UTF_8));
        assertTrue(PrefsXml.updateTextureCompression(file, false));
        Document xml = read(file);
        assertEquals("1280", xml.getElementsByTagName("screenWidth").item(0).getTextContent());
        assertEquals("False", xml.getElementsByTagName("fullscreen").item(0).getTextContent());
        assertEquals("Russian", xml.getElementsByTagName("language").item(0).getTextContent());
        assertEquals(1, xml.getElementsByTagName("textureCompression").getLength());
        assertEquals("False", xml.getElementsByTagName("textureCompression").item(0).getTextContent());
    }

    @Test public void missingTagIsInsertedIntoExistingPrefs() throws Exception {
        File file = tmp.newFile("Prefs.xml");
        Files.write(file.toPath(), "<prefs><screenHeight>720</screenHeight></prefs>".getBytes(StandardCharsets.UTF_8));
        assertTrue(PrefsXml.updateTextureCompression(file, false));
        Document xml = read(file);
        assertEquals("720", xml.getElementsByTagName("screenHeight").item(0).getTextContent());
        assertEquals("False", xml.getElementsByTagName("textureCompression").item(0).getTextContent());
    }

    @Test public void repeatedPinDoesNotRewriteFile() throws Exception {
        File file = new File(tmp.getRoot(), "Prefs.xml");
        PrefsXml.updateTextureCompression(file, false);
        byte[] before = Files.readAllBytes(file.toPath());
        assertFalse(PrefsXml.updateTextureCompression(file, false));
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
    }

    @Test public void explicitOnStillWorksForZink() throws Exception {
        File file = new File(tmp.getRoot(), "Prefs.xml");
        assertTrue(PrefsXml.updateTextureCompression(file, true));
        assertEquals("True", read(file).getElementsByTagName("textureCompression").item(0).getTextContent());
    }
}
