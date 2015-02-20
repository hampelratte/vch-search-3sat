package de.berlios.vch.search.dreisat;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import de.berlios.vch.parser.IVideoPage;

public class SmilParser {

    public static IVideoPage parseVideoUri(IVideoPage video, String smil) throws SAXException, IOException, ParserConfigurationException, URISyntaxException {
        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(smil)));

        String app = "ondemand";
        String host = "cp125301.edgefcs.net";

        NodeList params = doc.getElementsByTagName("param");
        for (int i = 0; i < params.getLength(); i++) {
            Node param = params.item(i);
            if ("app".equals(param.getAttributes().getNamedItem("name").getNodeValue())) {
                app = param.getAttributes().getNamedItem("value").getNodeValue();
            } else if ("host".equals(param.getAttributes().getNamedItem("name").getNodeValue())) {
                host = param.getAttributes().getNamedItem("value").getNodeValue();
            }
        }

        NodeList videos = doc.getElementsByTagName("video");
        // choose the last element, because the videos seem to be ordered by quality
        Node videoNode = videos.item(videos.getLength() - 1);
        String src = videoNode.getAttributes().getNamedItem("src").getNodeValue();
        video.setVideoUri(new URI("rtmp://" + host + "/" + app + "/" + src));
        video.getUserData().put("streamName", src);
        return video;
    }
}
