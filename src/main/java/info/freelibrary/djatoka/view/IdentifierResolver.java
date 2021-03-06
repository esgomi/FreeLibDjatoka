
package info.freelibrary.djatoka.view;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.List;
import java.util.Arrays;

import java.util.concurrent.CopyOnWriteArrayList;

import gov.lanl.adore.djatoka.openurl.DjatokaImageMigrator;
import gov.lanl.adore.djatoka.openurl.IReferentMigrator;
import gov.lanl.adore.djatoka.openurl.IReferentResolver;
import gov.lanl.adore.djatoka.openurl.ResolverException;

import gov.lanl.adore.djatoka.util.ImageRecord;

import info.freelibrary.djatoka.Constants;

import info.freelibrary.util.PairtreeObject;
import info.freelibrary.util.PairtreeRoot;
import info.freelibrary.util.PairtreeUtils;
import info.freelibrary.util.StringUtils;

import info.openurl.oom.entities.Referent;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IdentifierResolver implements IReferentResolver, Constants {

    private static final Logger LOGGER = LoggerFactory
            .getLogger(IdentifierResolver.class);

    private IReferentMigrator myMigrator = new DjatokaImageMigrator();

    private Map<String, ImageRecord> myRemoteImages;

    private List<String> myIngestSources = new CopyOnWriteArrayList<String>();

    private List<String> myIngestGuesses = new CopyOnWriteArrayList<String>();

    private File myJP2Dir;

    /**
     * Gets the image record for the requested image.
     * 
     * @param aRequest An image request
     * @return An image record
     */
    public ImageRecord getImageRecord(String aRequest) throws ResolverException {
        String decodedRequest;
        ImageRecord image;

        try {
            decodedRequest = URLDecoder.decode(aRequest, "UTF-8");
            decodedRequest = URLDecoder.decode(decodedRequest, "UTF-8");
        } catch (UnsupportedEncodingException details) {
            // Should not be possible; JVMs must support UTF-8
            throw new RuntimeException(details);
        }

        // Check to see if the image is resolvable from a remote source
        if (isResolvableURI(aRequest)) {
            String referent;

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Found a remotely resolvable ID: {}", aRequest);
            }

            try {
                referent = parseReferent(decodedRequest);
            } catch (UnsupportedEncodingException details) {
                // Should not be possible; JVMs must support UTF-8
                throw new RuntimeException(details);
            }

            // Check and see if we've already put it in the Pairtree FS
            image = getCachedImage(referent);

            // Otherwise, we retrieve the image from the remote source
            if (image == null) {
                image = getRemoteImage(referent, aRequest);
            }
        } else {
            image = getCachedImage(decodedRequest);

            // If we can't find the "non-remote" image in our local cache,
            // make one last ditch attempt to find it as a remote image...
            if (image == null) {
                for (int index = 0; index < myIngestGuesses.size(); index++) {
                    String urlPattern = myIngestGuesses.get(index);
                    String url = StringUtils.format(urlPattern, decodedRequest);

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Trying to resolve using URL pattern: {}",
                                url);
                    }

                    image = getRemoteImage(aRequest, url);
                }
            }
        }

        return image;
    }

    /**
     * Gets an image record for the supplied referent.
     * 
     * @param aReferent A referent for the desired image
     * @return An image record
     */
    public ImageRecord getImageRecord(Referent aReferent)
            throws ResolverException {
        String id = ((URI) aReferent.getDescriptors()[0]).toASCIIString();
        return getImageRecord(id);
    }

    /**
     * Gets the referent migrator for this resolver.
     */
    public IReferentMigrator getReferentMigrator() {
        return myMigrator;
    }

    /**
     * Gets the HTTP status of the referent ID request.
     * 
     * @param aReferentID The ID of a requested referent
     * @return An HTTP status code
     */
    public int getStatus(String aReferentID) {
        if (myRemoteImages.get(aReferentID) != null || // TODO: reversed?
                getCachedImage(aReferentID) != null) {
            return HttpServletResponse.SC_OK;
        } else if (myMigrator.getProcessingList().contains(aReferentID)) {
            return HttpServletResponse.SC_ACCEPTED;
        } else {
            return HttpServletResponse.SC_NOT_FOUND;
        }
    }

    /**
     * Sets the properties for this identifier resolver.
     * 
     * @param aProps A supplied properties configuration
     */
    public void setProperties(Properties aProps) throws ResolverException {
        String sources = aProps.getProperty("djatoka.known.ingest.sources");
        String guesses = aProps.getProperty("djatoka.known.ingest.guesses");

        myJP2Dir = new File(aProps.getProperty(JP2_DATA_DIR));
        myMigrator.setPairtreeRoot(myJP2Dir);
        myRemoteImages = new ConcurrentHashMap<String, ImageRecord>();

        myIngestSources.addAll(Arrays.asList(sources.split("\\s+")));
        myIngestGuesses.addAll(Arrays.asList(guesses.split("\\s+")));
    }

    private boolean isResolvableURI(String aReferentID) {
        return aReferentID.startsWith("http"); // keeping it simple
    }

    private ImageRecord getCachedImage(String aReferentID) {
        ImageRecord image = null;

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Checking in Pairtree file system for: {}",
                    aReferentID);
        }

        try {
            PairtreeRoot pairtree = new PairtreeRoot(myJP2Dir);
            PairtreeObject dir = pairtree.getObject(aReferentID);
            String filename = PairtreeUtils.encodeID(aReferentID);
            File file = new File(dir, filename);

            if (file.exists()) {
                image = new ImageRecord();
                image.setIdentifier(aReferentID);
                image.setImageFile(file.getAbsolutePath());

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("JP2 found in Pairtree cache: {}", file
                            .getAbsolutePath());
                }
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Failed to find a JP2 in Pairtree cache");
            }
        } catch (IOException details) {
            LOGGER.error("Failed to load file from cache", details);
        }

        if (LOGGER.isDebugEnabled() && image != null) {
            LOGGER.debug("** Returning JP2 image from getCachedImage() **");
        }

        return image;
    }

    private ImageRecord getRemoteImage(String aReferent, String aURL) {
        ImageRecord image = null;

        try {
            URI uri = new URI(aURL);
            File imageFile;

            // Check to see if it's already in the processing queue
            if (myMigrator.getProcessingList().contains(aReferent)) {
                Thread.sleep(1000);
                int index = 0;

                while (myMigrator.getProcessingList().contains(aReferent) &&
                        index < (5 * 60)) {
                    Thread.sleep(1000);
                    index++;
                }

                if (myRemoteImages.containsKey(aReferent)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Retrieving {} from remote images cache",
                                aReferent);
                    }

                    return myRemoteImages.get(aReferent);
                }
            }

            imageFile = myMigrator.convert(aReferent, uri);
            image = new ImageRecord(aReferent, imageFile.getAbsolutePath());

            if (imageFile.length() > 0) {
                myRemoteImages.put(aReferent, image);
            } else {
                throw new ResolverException(
                        "An error occurred processing file: " + uri.toURL());
            }
        } catch (Exception details) {
            LOGGER.error(StringUtils.format("Unable to access {} ({})",
                    aReferent, details.getMessage()));

            return null;
        }

        if (LOGGER.isDebugEnabled() && image != null) {
            LOGGER.debug("** Returning JP2 image from getRemoteImage() **");
        }

        return image;
    }

    private String parseReferent(String aReferent)
            throws UnsupportedEncodingException {
        String referent = aReferent;

        for (int index = 0; index < myIngestSources.size(); index++) {
            Pattern pattern = Pattern.compile(myIngestSources.get(index));
            Matcher matcher = pattern.matcher(referent);

            // If we have a parsable ID, let's use that instead of URI
            if (matcher.matches() && matcher.groupCount() > 0) {
                referent = URLDecoder.decode(matcher.group(1), "UTF-8");

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("ID '{}' extracted from a known pattern",
                            referent);
                }

                break; // We don't need to keep checking at this point
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("No Match in {} for {}", referent, pattern
                        .toString());
            }
        }

        return referent;
    }
}
