package org.openpnp.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.simpleframework.xml.Serializer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Keep Pro metadata out of XML read strictly by upstream OpenPnP 2.5. */
public final class ProConfigurationExtensions {
    private static final Map<String, Set<String>> ATTRIBUTES = new HashMap<>();
    static {
        ATTRIBUTES.put("package", names("aliases,category-id,classification-confidence,classification-reason,"
                + "duplicate-group-id,geometry-fingerprint,import-batch-id,last-imported-at,last-reviewed-at,"
                + "orientation-adjustment,orientation-reason,related-package-id,review-state,source-reference,"
                + "source-type,standard-name"));
        ATTRIBUTES.put("part", names("aliases,display-id,variant"));
        ATTRIBUTES.put("placement", names("display-id"));
        ATTRIBUTES.put("feeder", names("auto-align-confidence-adjustment,auto-align-confidence-percent"));
    }

    private ProConfigurationExtensions() { }

    private static Set<String> names(String csv) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(csv.split(","))));
    }

    public static Path sidecar(File file) {
        return file.toPath().resolveSibling(file.getName() + ".pro.xml");
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static byte[] bytes(Document document) throws Exception {
        TransformerFactory factory = TransformerFactory.newDefaultInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        factory.newTransformer().transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    private static String key(Element element) {
        if (!(element.getParentNode() instanceof Element)) {
            return "/" + element.getTagName();
        }
        Element parent = (Element) element.getParentNode();
        String identity = element.getAttribute("id");
        if (!identity.isEmpty()) {
            // Length prefix makes arbitrary identifiers unambiguous without XPath interpolation.
            return key(parent) + "/" + element.getTagName() + "#" + identity.length() + ":" + identity;
        }
        int index = 0;
        for (org.w3c.dom.Node n = element.getPreviousSibling(); n != null; n = n.getPreviousSibling()) {
            if (n instanceof Element && ((Element) n).getTagName().equals(element.getTagName())) {
                index++;
            }
        }
        return key(parent) + "/" + element.getTagName() + "[" + index + "]";
    }

    public static <T> T read(Serializer serializer, Class<T> type, File file) throws Exception {
        migrate(file);
        Path extra = sidecar(file);
        if (!Files.exists(extra)) {
            return serializer.read(type, file);
        }
        Document common = merged(file);
        return serializer.read(type, new StringReader(new String(bytes(common), java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static Document merged(File file) throws Exception {
        Document common = parse(Files.readAllBytes(file.toPath()));
        if (!Files.exists(sidecar(file))) {
            return common;
        }
        Document extensions = parse(Files.readAllBytes(sidecar(file)));
        Map<String, Element> targets = new HashMap<>();
        NodeList all = common.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (ATTRIBUTES.containsKey(element.getTagName())) {
                String key = key(element);
                if (targets.containsKey(key)) {
                    throw new Exception("Pro metadata contains duplicate object identity: " + key);
                }
                targets.put(key, element);
            }
        }
        NodeList entries = extensions.getElementsByTagName("attribute");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            Element target = targets.get(entry.getAttribute("key"));
            String name = entry.getAttribute("name");
            if (target != null && ATTRIBUTES.get(target.getTagName()).contains(name)
                    && !target.hasAttribute(name)) {
                target.setAttribute(name, entry.getAttribute("value"));
            }
        }
        return common;
    }

    /** One-time migration retains a complete legacy file before moving metadata. */
    public static void migrate(File file) throws Exception {
        Document common = parse(Files.readAllBytes(file.toPath()));
        boolean legacy = false;
        NodeList all = common.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            for (String name : ATTRIBUTES.getOrDefault(element.getTagName(), Collections.emptySet())) {
                legacy |= element.hasAttribute(name);
            }
        }
        if (!legacy || !Files.isWritable(file.toPath())) {
            return;
        }
        byte[] complete = bytes(merged(file));
        Path backup = file.toPath().resolveSibling(file.getName() + ".pre-pro-compat.bak");
        if (!Files.exists(backup)) {
            Files.copy(file.toPath(), backup);
        }
        write(file, complete);
    }

    /** Both complete files are staged before either existing file is replaced. */
    public static void write(File file, byte[] serialized) throws Exception {
        Document common = parse(serialized);
        Document extensions = parse("<pro-extensions version=\"1\"/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        NodeList all = common.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element target = (Element) all.item(i);
            for (String name : ATTRIBUTES.getOrDefault(target.getTagName(), Collections.emptySet())) {
                if (target.hasAttribute(name)) {
                    Element entry = extensions.createElement("attribute");
                    entry.setAttribute("key", key(target));
                    entry.setAttribute("name", name);
                    entry.setAttribute("value", target.getAttribute(name));
                    extensions.getDocumentElement().appendChild(entry);
                    target.removeAttribute(name);
                }
            }
        }
        byte[] base = bytes(common), extra = bytes(extensions);
        Path destination = file.toPath().toAbsolutePath();
        Path overlay = sidecar(file).toAbsolutePath();
        Path baseTemp = Files.createTempFile(destination.getParent(), "openpnp-base-", ".tmp");
        Path extraTemp = Files.createTempFile(destination.getParent(), "openpnp-pro-", ".tmp");
        try {
            Files.write(baseTemp, base);
            Files.write(extraTemp, extra);
            if (Files.exists(overlay)) {
                Files.copy(overlay, overlay.resolveSibling(overlay.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            replace(extraTemp, overlay);
            replace(baseTemp, destination);
        } finally {
            Files.deleteIfExists(baseTemp);
            Files.deleteIfExists(extraTemp);
        }
    }

    private static void replace(Path source, Path destination) throws Exception {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
