package de.berlios.vch.search.dreisat;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.osgi.service.log.LogService;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import de.berlios.vch.http.client.HttpUtils;
import de.berlios.vch.parser.HtmlParserUtils;
import de.berlios.vch.parser.IOverviewPage;
import de.berlios.vch.parser.IVideoPage;
import de.berlios.vch.parser.IWebPage;
import de.berlios.vch.parser.OverviewPage;
import de.berlios.vch.parser.VideoPage;
import de.berlios.vch.parser.XmlParserUtils;
import de.berlios.vch.search.ISearchProvider;

@Component
@Provides
public class DreisatSearchProvider implements ISearchProvider {

    public static final String BASE_URI = "http://www.3sat.de";

    public static final String XMLSERVICE_URI = BASE_URI + "/mediathek/xmlservice/web/beitragsDetails?ak=web&id=";

    public static final String MEDIATHEK_URI = BASE_URI + "/mediathek/mediathek.php";

    private static final String SEARCH_PAGE = MEDIATHEK_URI + "?mode=search&query=";

    public static final String CHARSET = "UTF-8";

    public static Map<String, String> HTTP_HEADERS = new HashMap<String, String>();
    static {
        HTTP_HEADERS.put("User-Agent", "Mozilla/5.0 (X11; Linux i686; rv:35.0) Gecko/20100101 Firefox/35.0");
        HTTP_HEADERS.put("Accept-Language", "de-de,de;q=0.8,en-us;q=0.5,en;q=0.3");
    }

    @Requires
    private LogService logger;

    @Override
    public String getName() {
        return "3sat";
    }

    @Override
    public IOverviewPage search(String query) throws Exception {
        // execute the search
        String uri = SEARCH_PAGE + URLEncoder.encode(query, "UTF-8");
        String content = HttpUtils.get(uri, null, "UTF-8");

        // parse the result and create an overview page
        IOverviewPage opage = new OverviewPage();
        opage.setParser(getId());
        opage.setUri(new URI(uri));
        parseResult(opage, content);
        return opage;
    }

    private void parseResult(IOverviewPage opage, String content) throws Exception {
        Elements rows = HtmlParserUtils.getTags(content, "div.meditheklistbox_MainBox div[class^=mediatheklistbox][class$=_hover]");
        for (Element row : rows) {
            String rowContent = row.html();

            // create a new subpage of type IVideoPage
            IVideoPage video = new VideoPage();
            video.setParser(getId());
            opage.getPages().add(video);

            // parse the page uri
            Element a = HtmlParserUtils.getTag(rowContent, "div[class~=mediathek_ListBoxHeadline] a[title]");
            String uri = MEDIATHEK_URI + a.attr("href");
            video.setUri(new URI(uri));

            // parse the thumb
            Element img = HtmlParserUtils.getTag(rowContent, "div[class~=MediathekListPic] a img");
            video.setThumbnail(new URI(BASE_URI + img.attr("src")));

            // parse the title
            video.setTitle(a.text());

            // parse the duration
            video.setDuration(parseDuration(rowContent));
        }
    }

    @Override
    public String getId() {
        return DreisatSearchProvider.class.getName();
    }

    @Override
    public IWebPage parse(IWebPage page) throws Exception {
        if (page instanceof IVideoPage) {
            IVideoPage video = (IVideoPage) page;
            parseVideo(video);
        }
        return page;
    }

    private void parseVideo(IVideoPage video) throws XPathExpressionException, IOException, URISyntaxException, SAXException, ParserConfigurationException {
        String content = HttpUtils.get(video.getUri().toString(), HTTP_HEADERS, CHARSET);
        Matcher assetIdMatcher = Pattern.compile("assetID\\s*:\\s*(\\d+)").matcher(content);
        if (assetIdMatcher.find()) {
            String assetId = assetIdMatcher.group(1);
            logger.log(LogService.LOG_DEBUG, "Asset ID: " + assetId);
            String xml = HttpUtils.get(XMLSERVICE_URI + assetId, null, CHARSET);
            String statusCode = XmlParserUtils.getStringWithXpath(xml, "/response/status/statuscode");
            if (!"ok".equals(statusCode)) {
                throw new RuntimeException("XML service responded with status " + statusCode);
            }

            // title
            String program = XmlParserUtils.getStringWithXpath(xml, "/response/video/details/originChannelTitle");
            String episode = XmlParserUtils.getStringWithXpath(xml, "/response/video/information/title");
            video.setTitle(program + " - " + episode);

            // desc
            String desc = XmlParserUtils.getStringWithXpath(xml, "/response/video/information/detail");
            video.setDescription(desc);

            // duration
            int duration = Integer.parseInt(XmlParserUtils.getStringWithXpath(xml, "/response/video/details/lengthSec"));
            video.setDuration(duration);

            // publish date
            try {
                String airtime = XmlParserUtils.getStringWithXpath(xml, "/response/video/details/airtime");
                Date airtimeDate = new SimpleDateFormat("dd.MM.yyyy HH:mm").parse(airtime);
                Calendar pubDate = Calendar.getInstance();
                pubDate.setTime(airtimeDate);
                video.setPublishDate(pubDate);
            } catch (Exception e) {
                logger.log(LogService.LOG_WARNING, "Couldn't parse publish date");
            }

            // thumbnail
            String thumbUri = XmlParserUtils.getStringWithXpath(xml, "/response/video/teaserimages/teaserimage[last()]");
            video.setThumbnail(new URI(thumbUri));

            // parse video URI
            // get the first smil file. which one does not matter, because they all have the same content
            NodeList formitaeten = XmlParserUtils.getNodeListWithXpath(xml, "/response/video/formitaeten/formitaet[@basetype='h264_aac_mp4_rtmp_smil_http']");
            if (formitaeten.getLength() > 0) {
                Node formitaet = formitaeten.item(0);
                String smilUri = XmlParserUtils.getStringWithXpath(formitaet, "url");
                String smilContent = HttpUtils.get(smilUri, HTTP_HEADERS, CHARSET);
                SmilParser.parseVideoUri(video, smilContent);
            } else {
                throw new RuntimeException("No videos found in XML " + XMLSERVICE_URI + assetId);
            }
        } else {
            throw new RuntimeException("Asset ID not found in HTML");
        }
    }

    private long parseDuration(String text) {
        Matcher m = Pattern.compile("(\\d{1,2})\\:(\\d{2})\\s*min").matcher(text);
        if (m.find()) {
            int minutes = Integer.parseInt(m.group(1));
            int seconds = Integer.parseInt(m.group(2));
            return (minutes * 60 + seconds);
        } else {
            logger.log(LogService.LOG_DEBUG, "Haut nicht hin " + text);
        }

        return -1;
    }
}
