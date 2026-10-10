/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.core.evidence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Puts the signed evidence into the face BIR's {@code Others} and nowhere else (Bible Part 14; Build Map
 * #60). MOSIP's own schema ({@code kernel-cbeffutil-api} 1.2.0.3, {@code cbeff.xsd}) defines {@code Others}
 * as repeated {@code <Others><Key/><Value/></Others>} elements, last in a BIR's sequence after
 * {@code BDB} and {@code SB}; new elements take the namespace of the BIR they join. {@code BDB},
 * {@code BDBInfo} and every other BIR are left exactly as they were.
 *
 * <p>A CBEFF from a packet is untrusted input: DOCTYPEs are refused, so no external entity is ever resolved.
 */
public final class CbeffOthers {

    public static final String KEY = "PRAMAAN_LIVENESS";

    private CbeffOthers() {
    }

    /** Sets {@code key} to {@code value} in the one BIR of this biometric type, replacing any previous value. */
    public static byte[] embed(byte[] cbeffXml, String bioType, String key, String value) throws IOException {
        Document doc = parse(cbeffXml);
        Element bir = theBir(doc, bioType);
        for (Element others : children(bir, "Others")) {
            if (key.equals(text(first(others, "Key")))) {
                bir.removeChild(others);
            }
        }
        String ns = bir.getNamespaceURI();
        String prefix = bir.getPrefix();
        Element others = doc.createElementNS(ns, qualified(prefix, "Others"));
        Element k = doc.createElementNS(ns, qualified(prefix, "Key"));
        k.setTextContent(key);
        Element v = doc.createElementNS(ns, qualified(prefix, "Value"));
        v.setTextContent(value);
        others.appendChild(k);
        others.appendChild(v);
        bir.appendChild(others);
        return serialise(doc);
    }

    public static Optional<String> read(byte[] cbeffXml, String bioType, String key) throws IOException {
        Element bir = theBir(parse(cbeffXml), bioType);
        for (Element others : children(bir, "Others")) {
            if (key.equals(text(first(others, "Key")))) {
                return Optional.ofNullable(text(first(others, "Value")));
            }
        }
        return Optional.empty();
    }

    /** The {@code BDB} text of the BIR of this type: what an ABIS matcher reads, which embedding must not change. */
    public static String bdb(byte[] cbeffXml, String bioType) throws IOException {
        return text(first(theBir(parse(cbeffXml), bioType), "BDB"));
    }

    static Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("not a well-formed CBEFF document: " + e.getMessage(), e);
        }
    }

    private static byte[] serialise(Document doc) throws IOException {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.STANDALONE, "yes");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (TransformerException e) {
            throw new IOException("could not write the CBEFF document: " + e.getMessage(), e);
        }
    }

    /** The single BIR whose own BDBInfo/Type names this biometric type. */
    private static Element theBir(Document doc, String bioType) throws IOException {
        List<Element> matches = new ArrayList<>();
        NodeList all = doc.getElementsByTagNameNS("*", "BIR");
        for (int i = 0; i < all.getLength(); i++) {
            Element bir = (Element) all.item(i);
            Element info = first(bir, "BDBInfo");
            Element type = info == null ? null : first(info, "Type");
            String t = text(type);
            if (t != null && t.trim().toLowerCase(Locale.ROOT).equals(bioType.toLowerCase(Locale.ROOT))) {
                matches.add(bir);
            }
        }
        if (matches.size() != 1) {
            throw new IOException("expected exactly one " + bioType + " BIR, found " + matches.size());
        }
        return matches.get(0);
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && localName.equals(n.getLocalName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static Element first(Element parent, String localName) {
        List<Element> c = children(parent, localName);
        return c.isEmpty() ? null : c.get(0);
    }

    private static String text(Element e) {
        return e == null ? null : e.getTextContent();
    }

    private static String qualified(String prefix, String local) {
        return prefix == null || prefix.isEmpty() ? local : prefix + ":" + local;
    }
}
