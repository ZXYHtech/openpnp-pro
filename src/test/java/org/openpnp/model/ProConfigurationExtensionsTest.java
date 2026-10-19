package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;
import org.simpleframework.xml.core.Persister;
import org.simpleframework.xml.stream.Format;
import org.simpleframework.xml.stream.HyphenStyle;
import java.util.List;

public class ProConfigurationExtensionsTest {
    @Root(name = "openpnp-packages")
    public static class Upstream {
        @ElementList(inline = true, entry = "package")
        List<BasePackage> packages;
    }
    @Root(name = "package")
    public static class BasePackage {
        @Attribute String id;
        @Attribute(required = false) String description;
    }

    @Test
    public void upstreamReadsBaseAndMetadataFollowsIdentityAfterReorder() throws Exception {
        Path directory = Files.createTempDirectory("pro-compat-");
        Path file = directory.resolve("packages.xml");
        String source = "<openpnp-packages><package id='A' geometry-fingerprint='hash' aliases='甲'/><package id='B' aliases='乙'/></openpnp-packages>";
        ProConfigurationExtensions.write(file.toFile(), source.getBytes(StandardCharsets.UTF_8));
        Persister serializer = new Persister(new Format(new HyphenStyle()));
        Upstream original = serializer.read(Upstream.class, file.toFile());
        assertEquals(2, original.packages.size());
        assertFalse(Files.readString(file).contains("geometry-fingerprint"));
        Files.writeString(file, "<openpnp-packages><package id='B' description='edited'/><package id='A'/></openpnp-packages>");
        Extended restored = ProConfigurationExtensions.read(serializer, Extended.class, file.toFile());
        assertEquals("B", restored.packages.get(0).id);
        assertEquals("乙", restored.packages.get(0).aliases);
        assertEquals("edited", restored.packages.get(0).description);
        assertEquals("hash", restored.packages.get(1).geometryFingerprint);
    }

    @Root(name = "openpnp-packages")
    public static class Extended {
        @ElementList(inline = true, entry = "package") List<ExtendedPackage> packages;
    }
    @Root(name = "package")
    public static class ExtendedPackage extends BasePackage {
        @Attribute(required = false) String geometryFingerprint;
        @Attribute(required = false) String aliases;
    }

    @Test
    public void deletedIdentityIsNotAttachedToReplacementAndInlineValueWins() throws Exception {
        Path file = Files.createTempDirectory("pro-compat-").resolve("packages.xml");
        ProConfigurationExtensions.write(file.toFile(),
                "<openpnp-packages><package id='A' aliases='old'/></openpnp-packages>".getBytes(StandardCharsets.UTF_8));
        Files.writeString(file, "<openpnp-packages><package id='B'/><package id='A' aliases='current'/></openpnp-packages>");
        Extended restored = ProConfigurationExtensions.read(new Persister(new Format(new HyphenStyle())), Extended.class, file.toFile());
        assertNull(restored.packages.get(0).aliases);
        assertEquals("current", restored.packages.get(1).aliases);
    }

    @Test
    public void malformedXmlCannotReplaceExistingFiles() throws Exception {
        Path file = Files.createTempDirectory("pro-compat-").resolve("parts.xml");
        Files.writeString(file, "original");
        try {
            ProConfigurationExtensions.write(file.toFile(), "<broken>".getBytes(StandardCharsets.UTF_8));
            fail("Malformed input accepted");
        } catch (Exception expected) {
            assertEquals("original", Files.readString(file));
        }
    }
}
