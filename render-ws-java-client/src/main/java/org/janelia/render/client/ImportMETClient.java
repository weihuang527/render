package org.janelia.render.client;

import com.beust.jcommander.Parameter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.PolynomialTransform2D;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ListTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.TransformSpec;
import org.janelia.alignment.spec.validator.TemTileSpecValidator;
import org.janelia.alignment.util.ProcessTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java client for generating importing MET data from stitching and alignment processes into the render database.
 *
 * @author Eric Trautman
 */
public class ImportMETClient {

    @SuppressWarnings("ALL")
    private static class Parameters extends RenderDataClientParameters {

        // NOTE: --baseDataUrl, --owner, and --project parameters defined in RenderDataClientParameters

        @Parameter(names = "--acquireStack", description = "Name of source (acquire) stack containing base tile specifications", required = true)
        private String acquireStack;

        @Parameter(names = "--alignStack", description = "Name of target (align, montage, etc.) stack that will contain imported transforms", required = true)
        private String alignStack;

        @Parameter(names = "--metFile", description = "MET file for section", required = true)
        private String metFile;

        @Parameter(names = "--formatVersion", description = "MET format version ('v1', v2', ...), default is 'v1'", required = false)
        private String formatVersion = "v1";

        @Parameter(names = "--replaceAll", description = "Replace all transforms with the MET transform (default is to only replace the last transform)", required = false, arity = 0)
        private boolean replaceAll;
    }

    public static void main(final String[] args) {
        try {
            final Parameters parameters = new Parameters();
            parameters.parse(args);

            LOG.info("main: entry, parameters={}", parameters);

            final ImportMETClient client = new ImportMETClient(parameters);

            client.generateStackData();

        } catch (final Throwable t) {
            LOG.error("main: caught exception", t);
            System.exit(1);
        }
    }

    private final Parameters parameters;

    private final RenderDataClient renderDataClient;

    private final Map<String, SectionData> metSectionToDataMap;

    public ImportMETClient(final Parameters parameters) {
        this.parameters = parameters;
        this.renderDataClient = parameters.getClient();
        this.metSectionToDataMap = new HashMap<>();
    }

    public void generateStackData() throws Exception {

        LOG.info("generateStackData: entry");

        if ("v2".equalsIgnoreCase(parameters.formatVersion)) {
            loadMetV2Data();
        } else {
            loadMetV1Data();
        }

        for (final SectionData sectionData : metSectionToDataMap.values()) {
            sectionData.updateTiles();
        }

        // only save updated data if all updates completed successfully
        for (final SectionData sectionData : metSectionToDataMap.values()) {
            sectionData.saveTiles();
        }

        LOG.info("generateStackData: exit, saved tiles and transforms for {}", metSectionToDataMap.values());
    }

    private void loadMetData(final int[] parameterIndexes,
                             final int lastParameterIndex,
                             final CoordinateTransform transformModel)
            throws IOException, IllegalArgumentException {

        final Path path = FileSystems.getDefault().getPath(parameters.metFile).toAbsolutePath();
        final String modelClassName = transformModel.getClass().getName();

        LOG.info("loadMetData: entry, formatVersion={}, modelClassName={}, path={}",
                 parameters.formatVersion, modelClassName, path);

        final BufferedReader reader = Files.newBufferedReader(path, Charset.defaultCharset());

        int lineNumber = 0;

        String line;
        String[] w;
        String section;
        String tileId;
        SectionData sectionData;
        final StringBuilder modelData = new StringBuilder(128);
        String modelDataString;
        while ((line = reader.readLine()) != null) {

            lineNumber++;

            w = WHITESPACE_PATTERN.split(line);

            if (w.length < lastParameterIndex) {

                LOG.warn("loadMetData: skipping line {} because it only contains {} words", lineNumber, w.length);

            } else {

                section = w[0];
                tileId = w[1];

                sectionData = metSectionToDataMap.get(section);

                if (sectionData == null) {
                    final TileSpec acquireTileSpec = renderDataClient.getTile(parameters.acquireStack, tileId);
                    final Double z = acquireTileSpec.getZ();
                    LOG.info("loadMetData: mapped section {} to z value {}", section, z);
                    sectionData = new SectionData(path, z);
                    metSectionToDataMap.put(section, sectionData);
                }

                modelData.setLength(0);
                for (int i = 0; i < parameterIndexes.length; i++) {
                    if (i > 0) {
                        modelData.append(' ');
                    }
                    modelData.append(w[parameterIndexes[i]]);
                }
                modelDataString = modelData.toString();

                try {
                    transformModel.init(modelDataString);
                } catch (final Exception e) {
                    throw new IllegalArgumentException("Failed to parse transform data from line " + lineNumber +
                                                       " of MET file " + path + ".  Invalid data string is '" +
                                                       modelDataString + "'.", e);
                }

                sectionData.addTileId(tileId, lineNumber, modelClassName, modelDataString);
            }

        }

        if (metSectionToDataMap.size() == 0) {
            throw new IllegalArgumentException("No tile information found in MET file " + path + ".");
        }

        LOG.info("loadMetData: exit, loaded {} lines for {}",
                 lineNumber, metSectionToDataMap.values());
    }

    private void loadMetV1Data()
            throws IOException, IllegalArgumentException {

        // MET v1 data format:
        //
        // section  tileId              ?  affineParameters (**NOTE: order 1-6 differs from renderer 1,4,2,5,3,6)
        // -------  ------------------  -  -------------------------------------------------------------------
        // 5100     140731162138009113  1  0.992264  0.226714  27606.648556  -0.085614  0.712238  38075.232380  9  113  0  /nobackup/flyTEM/data/whole_fly_1/141111-lens/140731162138_112x144/col0009/col0009_row0113_cam0.png  -999

        final int[] parameterIndexes = {3, 6, 4, 7, 5, 8};
        final int lastAffineParameter = 9;
        loadMetData(parameterIndexes, lastAffineParameter, new AffineModel2D());
    }

    private void loadMetV2Data()
            throws IOException, IllegalArgumentException {

        // MET v2 data format:
        //
        // section  tileId                  ?  polyParameters (12 values, same order as Saalfeld)                                                                                                                                                                                           ?   ?
        // -------  ------------------      -  -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------  -   --  -   --------------------------------------------------------------------------------------------------- -
        // 11	150226163251007079.3461.0	1	144835.943662000005	-0.960117997218	-0.069830998961	0.000000475771	0.000000631026	-0.000005306870	7651.469166999998	0.117266994598	-0.962169002963	-0.000000225265	-0.000003457168	0.000009180979	7	79	3	/nobackup/flyTEM/data/whole_fly_1/141111-lens/150226163251_120x172/col0007/col0007_row0079_cam3.png	7

        final int[] parameterIndexes = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
        final int lastAffineParameter = 15;
        loadMetData(parameterIndexes, lastAffineParameter, new PolynomialTransform2D());
    }

    private class SectionData {

        private final Path path;
        private final Double z;
        private final Map<String, TransformSpec> tileIdToAlignTransformMap;
        private ResolvedTileSpecCollection updatedTiles;

        private SectionData(final Path path,
                            final Double z) {
            this.path = path;
            this.z = z;
            final int capacityForLargeSection = (int) (5000 / 0.75);
            this.tileIdToAlignTransformMap = new HashMap<>(capacityForLargeSection);
            this.updatedTiles = null;
        }

        public int getUpdatedTileCount() {
            return updatedTiles == null ? 0 : updatedTiles.getTileCount();
        }

        @Override
        public String toString() {
            return "{z: " + z +
                   ", updatedTileCount: " + getUpdatedTileCount() +
                   '}';
        }

        public void addTileId(final String tileId,
                              final int lineNumber,
                              final String modelClassName,
                              final String modelDataString) {

            if (tileIdToAlignTransformMap.containsKey(tileId)) {
                throw new IllegalArgumentException("Tile ID " + tileId + " is listed more than once in MET file " +
                                                   path + ".  The second reference was found at line " +
                                                   lineNumber + ".");
            }

            tileIdToAlignTransformMap.put(tileId, new LeafTransformSpec(modelClassName, modelDataString));

        }

        public void updateTiles() throws Exception {

            LOG.info("updateTiles: entry, z={}", z);

            updatedTiles = renderDataClient.getResolvedTiles(parameters.acquireStack, z);

            //
            updatedTiles.setTileSpecValidator(new TemTileSpecValidator());

            if (!updatedTiles.hasTileSpecs()) {
                throw new IllegalArgumentException(updatedTiles + " does not have any tiles");
            }

            LOG.info("updateTiles: filtering tile spec collection {}", updatedTiles);

            updatedTiles.filterSpecs(tileIdToAlignTransformMap.keySet());

            if (!updatedTiles.hasTileSpecs()) {
                throw new IllegalArgumentException("after filtering out non-aligned tiles, " +
                                                   updatedTiles + " does not have any remaining tiles");
            }

            LOG.info("updateTiles: after filter, collection is {}", updatedTiles);

            final int transformTileCount = tileIdToAlignTransformMap.size();
            final ProcessTimer timer = new ProcessTimer();
            int tileSpecCount = 0;
            TransformSpec alignTransform;
            TileSpec tileSpec;
            for (final String tileId : tileIdToAlignTransformMap.keySet()) {
                alignTransform = tileIdToAlignTransformMap.get(tileId);

                if (parameters.replaceAll) {

                    tileSpec = updatedTiles.getTileSpec(tileId);

                    if (tileSpec == null) {
                        throw new IllegalArgumentException("tile spec with id '" + tileId +
                                                           "' not found in " + updatedTiles +
                                                           ", possible issue with z value");
                    }

                    tileSpec.setTransforms(new ListTransformSpec());
                }

                updatedTiles.addTransformSpecToTile(tileId, alignTransform, true);
                tileSpecCount++;
                if (timer.hasIntervalPassed()) {
                    LOG.info("updateTiles: updated transforms for {} out of {} tiles",
                             tileSpecCount, transformTileCount);
                }
            }

            final int removedTiles = tileSpecCount - updatedTiles.getTileCount();

            LOG.debug("updateTiles: updated transforms for {} tiles, removed {} bad tiles, elapsedSeconds={}",
                      tileSpecCount, removedTiles, timer.getElapsedSeconds());
        }

        public void saveTiles() throws Exception {

            LOG.info("saveTiles: entry, z={}", z);

            renderDataClient.saveResolvedTiles(updatedTiles, parameters.alignStack, z);

            LOG.info("saveTiles: exit, saved tiles and transforms for {}", z);
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(ImportMETClient.class);

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
}