package org.janelia.render.client.spark.cache;

import org.janelia.alignment.match.SurfFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache container for a canvas' list of SURF features.
 *
 * @author Eric Trautman
 */
public class CachedCanvasSurfFeatures
        implements CachedCanvasData {

    private final SurfFeatures featureList;

    public CachedCanvasSurfFeatures(final SurfFeatures featureList) {
        this.featureList = featureList;
    }

    public SurfFeatures getFeatureList() {
        return featureList;
    }

    public long getKilobytes() {
        return (long) (featureList.size() * AVERAGE_KILOBYTES_PER_FEATURE) + 1;
    }

    @Override
    public String toString() {
        return "featureList[" + featureList.size() + "]";
    }

    /** Since feature lists are only in-memory, this method is a no-op. */
    public void remove() {
        LOG.info("removed list with {} features", featureList.size());
    }

    /**
     * Average size of a feature.
     * This was derived from a 2K x 2K FAFB00 image and is hopefully good enough for most needs.
     */
    private static final double AVERAGE_KILOBYTES_PER_FEATURE = 0.6; // 600 bytes

    private static final Logger LOG = LoggerFactory.getLogger(CachedCanvasSurfFeatures.class);
}