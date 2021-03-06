
package info.freelibrary.djatoka.view;

import info.freelibrary.djatoka.iiif.Region;
import info.freelibrary.djatoka.iiif.ImageRequest;
import info.freelibrary.djatoka.iiif.IIIFRequest;

import gov.lanl.adore.djatoka.openurl.OpenURLJP2KService;

import info.freelibrary.djatoka.Constants;
import info.freelibrary.djatoka.util.CacheUtils;

import info.freelibrary.util.IOUtils;
import info.freelibrary.util.PairtreeObject;
import info.freelibrary.util.PairtreeRoot;
import info.freelibrary.util.PairtreeUtils;
import info.freelibrary.util.StringUtils;

import java.awt.image.BufferedImage;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Properties;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import nu.xom.Attribute;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.ParsingException;
import nu.xom.Serializer;
import nu.xom.ValidityException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImageServlet extends HttpServlet implements Constants {

    /**
     * The <code>ImageServlet</code>'s <code>serialVersionUID</code>.
     */
    private static final long serialVersionUID = -4142816720756238591L;

    private static final Logger LOGGER = LoggerFactory
            .getLogger(ImageServlet.class);

    private static final String IMAGE_URL =
            "/resolve?url_ver=Z39.88-2004&rft_id={}"
                    + "&svc_id=info:lanl-repo/svc/getRegion"
                    + "&svc_val_fmt=info:ofi/fmt:kev:mtx:jpeg2000"
                    + "&svc.format={}&svc.level={}&svc.rotate={}";

    private static final String REGION_URL =
            "/resolve?url_ver=Z39.88-2004&rft_id={}"
                    + "&svc_id=info:lanl-repo/svc/getRegion"
                    + "&svc_val_fmt=info:ofi/fmt:kev:mtx:jpeg2000"
                    + "&svc.format={}&svc.region={}&svc.scale={}&svc.rotate={}";

    private static final String DZI_TEMPLATE = "/WEB-INF/classes/dzi.xml";

    private static final String DZI_NS =
            "http://schemas.microsoft.com/deepzoom/2008";

    private static final String CHARSET = "UTF-8";

    private static String myFormatExt;

    private static String myCache;

    @Override
    protected void doGet(HttpServletRequest aRequest,
            HttpServletResponse aResponse) throws ServletException, IOException {
        String level = getServletConfig().getInitParameter("level");
        IIIFRequest iiif = (IIIFRequest) aRequest.getAttribute(IIIFRequest.KEY);
        String reqURI = aRequest.getRequestURI();
        String servletPath = aRequest.getServletPath();
        String path = reqURI.substring(servletPath.length());
        String id = getID(path);

        if (reqURI.endsWith("/info.xml") || reqURI.endsWith("/info.json")) {
            try {
                int[] dims = getHeightWidth(aRequest, aResponse);
                ImageInfo imageInfo = new ImageInfo(id, dims[0], dims[1]);
                ServletOutputStream outStream = aResponse.getOutputStream();

                if (reqURI.endsWith("/info.xml")) {
                    imageInfo.toStream(outStream);
                } else {
                    StringBuilder serviceSb = new StringBuilder();

                    serviceSb.append(aRequest.getScheme()).append("://");
                    serviceSb.append(aRequest.getServerName()).append(":");
                    serviceSb.append(aRequest.getServerPort());

                    String service = serviceSb.toString();
                    String prefix = iiif.getServicePrefix();

                    imageInfo.addFormat("jpg"); // FIXME: Configurable options

                    outStream.print(imageInfo.toJSON(service, prefix));
                }

                outStream.close();
            } catch (FileNotFoundException details) {
                aResponse.sendError(HttpServletResponse.SC_NOT_FOUND, id +
                        " not found");
            }
        } else if (iiif != null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Request is handled via the IIIFRequest shim");
            }

            ImageRequest imageRequest = (ImageRequest) iiif;
            String size = imageRequest.getSize().toString();
            Region iiifRegion = imageRequest.getRegion();
            float rotation = imageRequest.getRotation();
            String region;

            // Djatoka expects a different order from what OpenSeadragon sends
            // so we have to reconstruct rather than use Region's toString().
            if (iiifRegion.isFullSize()) {
                region = "";
            } else {
                StringBuilder rsb = new StringBuilder();
                rsb.append(iiifRegion.getY()).append(',');
                rsb.append(iiifRegion.getX()).append(',');
                rsb.append(iiifRegion.getHeight()).append(',');
                rsb.append(iiifRegion.getWidth());

                // Now, we have the string order that Djatoka wants
                region = rsb.toString();
            }

            if (myCache != null) {
                checkImageCache(id, level, size, region, rotation, aRequest,
                        aResponse);
            } else {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Cache isn't configured correctly");
                }

                serveNewImage(id, level, region, size, rotation, aRequest,
                        aResponse);
            }
        } else {
            // We are using the now deprecated FreeLib-Djatoka djtilesource.js
            String[] regionCoords = getRegion(path);
            String scale = getScale(path);
            String region;

            if (level == null && scale == null) {
                level = DEFAULT_VIEW_LEVEL;
            }

            if (regionCoords.length == 4) {
                region = StringUtils.toString(regionCoords, ',');
            } else {
                region = "";
            }

            if (LOGGER.isDebugEnabled()) {
                StringBuilder request = new StringBuilder();

                request.append("id[").append(id).append("] ");

                if (level != null) {
                    request.append("level[").append(level).append("] ");
                }

                if (scale != null) {
                    request.append("scale[").append(scale).append("] ");
                }

                request.append("region[").append(region).append("]");

                LOGGER.debug("Request: " + request.toString());
            }

            if (myCache != null) {
                // Older freelib-djatoka didn't support rotations; use 0.0f
                checkImageCache(id, level, scale, region, 0.0f, aRequest,
                        aResponse);
            } else {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Cache isn't configured correctly");
                }

                // Older freelib-djatoka didn't support rotations; use 0.0f
                serveNewImage(id, level, region, scale, 0.0f, aRequest,
                        aResponse);
            }
        }
    }

    @Override
    public void init() throws ServletException {
        InputStream is = getClass().getResourceAsStream("/" + PROPERTIES_FILE);

        if (is != null) {
            try {
                Properties props = new Properties();
                props.loadFromXML(is);

                if (props.containsKey(VIEW_CACHE_DIR)) {
                    myCache = props.getProperty(VIEW_CACHE_DIR);
                }

                // If we couldn't get cache from config, fall back to tmpdir
                if (myCache == null) {
                    myCache = System.getProperty("java.io.tmpdir");
                }

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Cache directory set to {}", myCache);
                }

                if (props.containsKey(VIEW_FORMAT_EXT)) {
                    myFormatExt =
                            props.getProperty(VIEW_FORMAT_EXT, DEFAULT_VIEW_EXT);

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Format extension set to {}", myFormatExt);
                    }
                }
            } catch (IOException details) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Unable to load properties file: {}", details
                            .getMessage());
                }
            } finally {
                IOUtils.closeQuietly(is);
            }
        }
    }

    @Override
    protected void doHead(HttpServletRequest aRequest,
            HttpServletResponse aResponse) throws ServletException, IOException {
        try {
            int[] dimensions = getHeightWidth(aRequest, aResponse);

            // TODO: add a content length header too
            if (!aResponse.isCommitted()) {
                aResponse.addIntHeader("X-Image-Height", dimensions[0]);
                aResponse.addIntHeader("X-Image-Width", dimensions[1]);

                aResponse.setStatus(HttpServletResponse.SC_OK);
            }
        } catch (FileNotFoundException details) {
            aResponse.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    @Override
    protected long getLastModified(HttpServletRequest aRequest) {
        // TODO: really implement this using our cached files?
        return super.getLastModified(aRequest);
    }

    private int[] getHeightWidth(HttpServletRequest aRequest,
            HttpServletResponse aResponse) throws IOException, ServletException {
        String reqURI = aRequest.getRequestURI();
        String servletPath = aRequest.getServletPath();
        String path = reqURI.substring(servletPath.length());
        String id = getID(path);
        int width = 0, height = 0;

        if (myCache != null) {
            OutputStream outStream = null;
            InputStream inStream = null;

            try {
                PairtreeRoot cacheDir = new PairtreeRoot(new File(myCache));
                PairtreeObject cacheObject = cacheDir.getObject(id);
                ServletContext context = getServletContext();
                String filename = PairtreeUtils.encodeID(id);
                // FIXME: Make this something other than a DZI file; IIIF XML?
                File dziFile = new File(cacheObject, filename + ".dzi");

                if (dziFile.exists() && dziFile.length() > 0) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Reading dzi file: " +
                                dziFile.getAbsolutePath());
                    }

                    Document dzi = new Builder().build(dziFile);
                    Element root = dzi.getRootElement();
                    Element size = root.getFirstChildElement("Size", DZI_NS);
                    String wString = size.getAttributeValue("Width");
                    String hString = size.getAttributeValue("Height");

                    width = wString.equals("") ? 0 : Integer.parseInt(wString);
                    height = hString.equals("") ? 0 : Integer.parseInt(hString);

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Returning width/height: {}/{}", width,
                                height);
                    }
                } else {
                    outStream = new FileOutputStream(dziFile);
                    inStream = context.getResource(DZI_TEMPLATE).openStream();

                    if (dziFile.exists()) {
                        if (!dziFile.delete() && LOGGER.isWarnEnabled()) {
                            LOGGER.warn("File not deleted: {}", dziFile);
                        }
                    }

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Creating new dzi file: " +
                                dziFile.getAbsolutePath());
                    }

                    Document dzi = new Builder().build(inStream);
                    Serializer serializer = new Serializer(outStream);
                    URL imageURL =
                            new URL(getFullSizeImageURL(aRequest) +
                                    URLEncoder.encode(id, "UTF-8"));

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Writing DZI file for: {}", imageURL
                                .toExternalForm());
                    }

                    try {
                        BufferedImage image = ImageIO.read(imageURL);
                        Element root = dzi.getRootElement();
                        Element size =
                                root.getFirstChildElement("Size", DZI_NS);
                        Attribute wAttribute = size.getAttribute("Width");
                        Attribute hAttribute = size.getAttribute("Height");

                        // Return the width and height in response headers
                        height = image.getHeight();
                        width = image.getWidth();

                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Returning width/height: {}/{}",
                                    width, height);
                        }

                        // Save it in our dzi file for easier access next time
                        wAttribute.setValue(Integer.toString(width));
                        hAttribute.setValue(Integer.toString(height));

                        serializer.write(dzi);
                    } catch (IIOException details) {
                        Class<?> thrown = details.getCause().getClass();
                        String name = thrown.getSimpleName();

                        if (name.equals("FileNotFoundException")) {
                            throw new FileNotFoundException(id + " not found");
                        } else {
                            if (LOGGER.isErrorEnabled()) {
                                LOGGER.error(details.getMessage() + " " +
                                        imageURL, details);
                            }

                            throw details;
                        }
                    }
                }
            } catch (ValidityException details) {
                aResponse.sendError(
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR, details
                                .getMessage());
            } catch (ParsingException details) {
                aResponse.sendError(
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR, details
                                .getMessage());
            } finally {
                IOUtils.closeQuietly(inStream);
                IOUtils.closeQuietly(outStream);
            }
        } else {
            // TODO: work around rather than throwing an exception
            throw new ServletException("Cache not correctly configured");
        }

        return new int[] {
            height, width
        };
    }

    private String getFullSizeImageURL(HttpServletRequest aRequest) {
        StringBuilder url = new StringBuilder();
        url.append(aRequest.getScheme()).append("://");
        url.append(aRequest.getServerName()).append(":");
        url.append(aRequest.getServerPort()).append("/");
        return url.append("view/fullSize/").toString();
    }

    private void checkImageCache(String aID, String aLevel, String aScale,
            String aRegion, float aRotation, HttpServletRequest aRequest,
            HttpServletResponse aResponse) throws IOException, ServletException {
        PairtreeRoot cacheDir = new PairtreeRoot(new File(myCache));
        PairtreeObject cacheObject = cacheDir.getObject(aID);
        String fileName =
                CacheUtils.getFileName(aLevel, aScale, aRegion, aRotation);
        File imageFile = new File(cacheObject, fileName);

        if (imageFile.exists()) {
            aResponse.setHeader("Content-Length", "" + imageFile.length());
            aResponse.setHeader("Cache-Control", "public, max-age=4838400");
            aResponse.setContentType("image/jpg");

            ServletOutputStream outStream = aResponse.getOutputStream();
            IOUtils.copyStream(imageFile, outStream);
            IOUtils.closeQuietly(outStream);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("{} served from Pairtree cache", imageFile);
            }
        } else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("{} not found in cache", imageFile);
            }

            serveNewImage(aID, aLevel, aRegion, aScale, aRotation, aRequest,
                    aResponse);
            cacheNewImage(aRequest, aID + "_" + fileName, imageFile);
        }
    }

    private void serveNewImage(String aID, String aLevel, String aRegion,
            String aScale, float aRotation, HttpServletRequest aRequest,
            HttpServletResponse aResponse) throws IOException, ServletException {
        String id = URLEncoder.encode(aID, CHARSET);
        RequestDispatcher dispatcher;
        String[] values;
        String url;

        // Cast floats as integers because that's what djatoka expects
        if (aScale == null) {
            values =
                    new String[] {
                        id, DEFAULT_VIEW_FORMAT, aLevel,
                        Integer.toString((int) aRotation)
                    };
            url = StringUtils.format(IMAGE_URL, values);
        } else {
            values =
                    new String[] {
                        id, DEFAULT_VIEW_FORMAT, aRegion,
                        aScale.equals("full") ? "1.0" : aScale,
                        Integer.toString((int) aRotation)
                    };
            url = StringUtils.format(REGION_URL, values);
        }

        // Right now we just let the OpenURL interface do the work
        dispatcher = aRequest.getRequestDispatcher(url);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Image requested: {} - {}", aID, url);
        }

        dispatcher.forward(aRequest, aResponse);
    }

    private void cacheNewImage(HttpServletRequest aRequest, String aKey,
            File aDestFile) {
        HttpSession session = aRequest.getSession();
        String fileName = (String) session.getAttribute(aKey);

        if (fileName != null) {
            String cacheName = (String) session.getAttribute(fileName);
            File cachedFile = new File(fileName);

            // This moves the newly created file from the adore-djatoka cache
            // to the freelib-djatoka cache (which is pure-FS/Pairtree-based)
            if (cachedFile.exists() && aDestFile != null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Renaming cache file from {} to {}",
                            cachedFile, aDestFile);
                }

                if (!cachedFile.renameTo(aDestFile) && LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Unable to move cache file: {}", cachedFile);
                } else {
                    // This is the temp file cache used by the OpenURL layer
                    if (!OpenURLJP2KService.removeFromTileCache(cacheName) &&
                            LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Unable to remove OpenURL cache file link: {}",
                                fileName);
                    } else {
                        session.removeAttribute(aKey);
                        session.removeAttribute(fileName);
                    }
                }
            } else if (LOGGER.isWarnEnabled() && !cachedFile.exists()) {
                LOGGER.warn(
                        "Session had a cache file ({}), but it didn't exist",
                        cachedFile.getAbsoluteFile());
            } else if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Location for destination cache file was null");
            }
        } else if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "Couldn't cache ({} = {}); session lacked new image information",
                    aKey, aDestFile.getAbsolutePath());
        }
    }

    /*
     * Working towards:
     * http://www.example.org/service/abcd1234/80,15,60,75/pct:100/0/color.jpg
     * /domain /service /ark /region /scale /rotation /filename /ext
     */

    private String getID(String aPath) {
        String path;

        if (aPath.startsWith("/")) {
            path = aPath.split("/")[1].replace('+', ' ');
        } else {
            path = aPath;
        }

        if (path.contains("%")) { // Path is URLEncoded
            try {
                path = URLDecoder.decode(path, "UTF-8");
                path = URLDecoder.decode(path, "UTF-8");
            } catch (UnsupportedEncodingException details) {
                // Never happens, all JVMs are required to support UTF-8
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("Couldn't decode path; no UTF-8 support");
                }
            }
        }

        return path;
    }

    private String[] getRegion(String aPathInfo) {
        String[] coordArray = new String[] {};

        if (aPathInfo.contains("/")) {
            String[] pathParts = aPathInfo.split("/");

            if (pathParts.length > 2) {
                String coordsString = aPathInfo.split("/")[2];

                if (!coordsString.equals("all") && !coordsString.equals("full")) {
                    String[] coords = coordsString.split(",");

                    if (coords.length == 4) {
                        coordArray = coords;
                    } else if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Invalid coordinates ({}) requested in: {}",
                                StringUtils.toString(coords, ','), aPathInfo);
                        // TODO: throw exception?
                    }
                }
            }
        }

        return coordArray;
    }

    private String getScale(String aPathInfo) {
        String scale = null;

        if (aPathInfo.contains("/")) {
            String[] pathParts = aPathInfo.split("/");

            if (pathParts.length > 3) {
                scale = aPathInfo.split("/")[3];
            }
        }

        if (scale != null && scale.startsWith("pct:")) {
            scale = scale.substring(4);
            scale = Double.toString(Double.parseDouble(scale) * .01);
        }

        return scale;
    }
}
