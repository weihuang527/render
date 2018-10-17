import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;
import mpicbg.trakem2.transform.TranslationModel2D;

import org.janelia.alignment.spec.LeafTransformSpec;
import org.janelia.alignment.spec.ResolvedTileSpecCollection;
import org.janelia.alignment.spec.SectionData;
import org.janelia.alignment.spec.TileSpec;
import org.janelia.alignment.spec.stack.StackId;
import org.janelia.alignment.spec.stack.StackMetaData;
import org.janelia.alignment.util.ProcessTimer;
import org.janelia.render.client.RenderDataClient;

import ini.trakem2.Project;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Utils;

/**
 * This plug-in exports TrakEM patch data into a render web services stack.
 *
 * @author Eric Trautman
 */
public class RenderWebServicesExport_Plugin
        implements PlugIn {

    @Override
    public void run(final String arg) {

        System.out.println(">>>> RenderWebServicesExport_Plugin.run: entry >>>>");

        PluginArgument pluginArgument = null;
        try {
            pluginArgument = PluginArgument.valueOf(arg);
        } catch (final IllegalArgumentException e) {
            e.printStackTrace(System.out);
            IJ.error("Unsupported Plugin Argument",
                     "unsupported arg '" + arg + "' specified for " + this.getClass().getName() + ", " +
                     "valid values are: " + Arrays.asList(PluginArgument.values()));
        }

        if (PluginArgument.EXPORT_TO_SERVICE.equals(pluginArgument)) {

            final List<Project> projectList = Project.getProjects();

            final LayerSet trakLayerSet;
            final Display front = Display.getFront();
            if (front == null) {
                final Project trakProject = projectList.get(0);
                trakLayerSet = trakProject.getRootLayerSet();
            } else {
                trakLayerSet = front.getLayerSet();
            }

            if (exportData == null) {
                exportData = new ExportData();
            }
            exportData.setTrakLayerSet(trakLayerSet);


            final boolean wasCancelled = exportData.loadRenderData();
            if (! wasCancelled) {
                try {
                    exportPatches();
                } catch (final Exception e) {
                    e.printStackTrace(System.out);
                    IJ.error("Export to Render Stack Failed",
                             "Caught exception:\n" + e.getMessage());
                }
            }

        }

        System.out.println("<<<< RenderWebServicesExport_Plugin.run: exit <<<<");
    }

    private void flattenTransforms(final CoordinateTransform ct,
                                   final List<CoordinateTransform> flattenedList) {
        if (ct instanceof CoordinateTransformList) {
            @SuppressWarnings("unchecked")
            final CoordinateTransformList<CoordinateTransform> ctList =
                    (CoordinateTransformList<CoordinateTransform>) ct;
            for (final CoordinateTransform ctListItem : ctList.getList(null)) {
                flattenTransforms(ctListItem, flattenedList);
            }
        } else {
            flattenedList.add(ct);
        }
    }

    private void exportPatches()
            throws IOException {

        final Layer firstLayer = exportData.trakLayerSet.getLayer(exportData.trakMinZ);
        final Layer lastLayer = exportData.trakLayerSet.getLayer(exportData.trakMaxZ);

        final Map<Double, ResolvedTileSpecCollection> zToTilesMap = new HashMap<>();
        final Map<Double, Set<String>> zToTileIdSetMap = new HashMap<>();

        final ProcessTimer timer = new ProcessTimer();

        for (final Layer layer : exportData.trakLayerSet.getLayers(firstLayer, lastLayer)) {

            // only grab visible patches since Stephan typically marks problem patches as not visible
            final List<Displayable> displayableList = layer.getDisplayables(Patch.class,
                                                                            true,
                                                                            true);

            if (displayableList.size() > 0) {

                final Patch firstPatch = (Patch) displayableList.get(0);
                final Matcher m = TILE_SECTION_ID_PATTERN.matcher(firstPatch.getTitle());
                if (! m.matches()) {
                    throw new IllegalArgumentException("cannot parse sectionId from patch title (tileId): " +
                                                       firstPatch.getTitle());
                }
                final String sectionId = m.group(1);
                final SectionData sectionData = exportData.sectionIdToDataMap.get(sectionId);
                final double basisZ = sectionData.getZ();

                ResolvedTileSpecCollection resolvedTiles = zToTilesMap.get(basisZ);
                Set<String> tileIdSet = zToTileIdSetMap.get(basisZ);
                if (resolvedTiles == null) {
                    resolvedTiles = exportData.basisRenderClient.getResolvedTiles(exportData.basisRenderStack, basisZ);
                    zToTilesMap.put(basisZ, resolvedTiles);
                    tileIdSet = new HashSet<>();
                    zToTileIdSetMap.put(basisZ, tileIdSet);
                }

                int updatedTileSpecCount = 0;
                for (final Displayable displayable : displayableList) {
                    final Patch patch = (Patch) displayable;

                    final String tileId = patch.getTitle();
                    final TileSpec tileSpec = resolvedTiles.getTileSpec(tileId);
                    tileSpec.removeLastTransformSpec();
                    tileIdSet.add(tileId);

                    if (exportData.hasMappedZValues()) {
                        tileSpec.setZ(exportData.getRenderStackZ(layer.getZ()));
                    }

                    final CoordinateTransform ct = patch.getFullCoordinateTransform();
                    final List<CoordinateTransform> flattenedTransformList = new ArrayList<>();
                    flattenTransforms(ct, flattenedTransformList);

                    // TODO: revisit these assumptions - is there a more generic way to handle?

                    // Current pattern is 2 lens correction transforms,
                    // followed by a stage translation transform, and finally an alignment transform.
                    // For other FlyTEM tools to work, we need to concatenate the stage and alignment
                    // transforms into one "last" transform and then replace the last transform
                    // from the basis stack tile spec with the concatenated alignment transform.

                    // After TrakEM2 montage process, tileId 150501185511004011.2429.3 transforms are:
                    //   mpicbg.trakem2.transform.NonLinearCoordinateTransform,'5 21 711.4721917133976 ... 0.0 2560 2160 '
                    //   mpicbg.trakem2.transform.AffineModel2D: '0.98033196 -0.011563861 -0.007845614 1.0093758 130.0 30.0'
                    //   mpicbg.trakem2.transform.TranslationModel2D: '7500.0 20150.0'
                    //   mpicbg.trakem2.transform.AffineModel2D: '0.9776400501038329 0.0025472555487284763 0.009202773959306552 1.0106124676219306 45979.12436456211 6001.50190550827'

                    final int stageTransformIndex = flattenedTransformList.size() - 2;
                    final int alignmentTransformIndex = flattenedTransformList.size() - 1;
                    final CoordinateTransform stageTransform = flattenedTransformList.get(stageTransformIndex);
                    final CoordinateTransform alignmentTransform = flattenedTransformList.get(alignmentTransformIndex);

                    if (stageTransform instanceof TranslationModel2D) {
                        if (alignmentTransform instanceof AffineModel2D) {
                            ((AffineModel2D) alignmentTransform).concatenate((TranslationModel2D) stageTransform);
                        } else {
                            throw new IllegalArgumentException("tile " + tileId + " alignment transform class is " +
                                                               alignmentTransform.getClass().getName());
                        }
                    } else {
                        throw new IllegalArgumentException("tile " + tileId + " stage transform class is " +
                                                           stageTransform.getClass().getName());
                    }

                    final LeafTransformSpec transformSpec = new LeafTransformSpec(alignmentTransform.getClass().getName(),
                                                                                  alignmentTransform.toDataString());
                    tileSpec.addTransformSpecs(Collections.singletonList(transformSpec));
                    updatedTileSpecCount++;

//                    // debug TrakEM2 transforms for the first few tiles ...
//                    if (updatedTileSpecCount < 5) {
//                        final StringBuilder msg = new StringBuilder("\ntileId: " + tileId + ":\n");
//                        for (final CoordinateTransform cot : flattenedTransformList) {
//                            msg.append(cot.getClass().getName()).append(": '").append(cot.toDataString()).append("'\n");
//                        }
//                        Utils.log(msg.append("\n"));
//                    }

                    if (timer.hasIntervalPassed()) {
                        Utils.log("exportPatches: updated " + updatedTileSpecCount + " out of " +
                                  displayableList.size() +" tile specs for section " + sectionId);
                    }
                }

                Utils.log("exportPatches: updated " + displayableList.size() +
                          " tile specs for section " + sectionId);
            }

        }

        for (final Double basisZ : zToTilesMap.keySet()) {

            final ResolvedTileSpecCollection resolvedTiles = zToTilesMap.get(basisZ);
            final Set<String> tileIdSet = zToTileIdSetMap.get(basisZ);
            resolvedTiles.removeDifferentTileSpecs(tileIdSet);

            Utils.log("exportPatches: updating bounding boxes for z " + basisZ);
            resolvedTiles.recalculateBoundingBoxes();

            // use null z value here since tiles might have been mapped to different z values above
            exportData.targetRenderClient.saveResolvedTiles(resolvedTiles, exportData.targetRenderStack, null);

            Utils.log("exportPatches: exported " + tileIdSet.size() + " tiles for z " + basisZ);

        }

        if (exportData.completeStackAfterExport) {
            exportData.targetRenderClient.setStackState(exportData.targetRenderStack,
                                                        StackMetaData.StackState.COMPLETE);
        }

        Utils.log("\nexportPatches: Done!");
    }

    private static ExportData exportData = null;

    private enum PluginArgument {
        EXPORT_TO_SERVICE
    }

    private class ExportData {

        private LayerSet trakLayerSet;

        private String baseDataUrl;
        private String basisRenderOwner;
        private String basisRenderProject;
        private String basisRenderStack;
        private double trakMinZ;
        private double trakMaxZ;
        private String targetRenderOwner;
        private String targetRenderProject;
        private String targetRenderStack;
        private String trakZToTargetZMapString;
        private boolean completeStackAfterExport;

        private RenderDataClient targetRenderClient;
        private RenderDataClient basisRenderClient;

        private final Map<String, SectionData> sectionIdToDataMap;
        private final Map<Double, Double> trakZToTargetZMap;

         ExportData() {
            baseDataUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";
            basisRenderOwner = "flyTEM";
            basisRenderProject = "FAFB00";
            basisRenderStack = "v12_acquire_merged";
            trakMinZ = 2429.0;
            trakMaxZ = 2429.3;
            targetRenderOwner = basisRenderOwner;
            targetRenderProject = "FAFB_montage";
            targetRenderStack = "trakem2_montage_2429_test_5";
            completeStackAfterExport = true;
            trakZToTargetZMapString = "2429.0=102524.0,2429.05=102525.0,2429.1=102526.0,2429.2=102527.0,2429.3=102528.0";

            sectionIdToDataMap = new HashMap<>();
            trakZToTargetZMap = new HashMap<>();
        }

        void setTrakLayerSet(final LayerSet trakLayerSet) {
            this.trakLayerSet = trakLayerSet;
        }

        boolean setParametersFromDialog() {

            final GenericDialog dialog = new GenericDialog("Export Parameters");

            final int defaultTextColumns = 100;
            dialog.addStringField("Render Web Services Base URL", baseDataUrl, defaultTextColumns);
            dialog.addStringField("Basis Render Stack Owner", basisRenderOwner, defaultTextColumns);
            dialog.addStringField("Basis Render Stack Project", basisRenderProject, defaultTextColumns);
            dialog.addStringField("Basis Render Stack Name", basisRenderStack, defaultTextColumns);
            dialog.addNumericField("TrakEM2 Min Z", trakMinZ, 1);
            dialog.addNumericField("TrakEM2 Max Z", trakMaxZ, 1);
            dialog.addStringField("Target Render Stack Owner", targetRenderOwner, defaultTextColumns);
            dialog.addStringField("Target Render Stack Project", targetRenderProject, defaultTextColumns);
            dialog.addStringField("Target Render Stack Name", targetRenderStack, defaultTextColumns);
            dialog.addStringField("TrakEM2 Z to Target Z Map", trakZToTargetZMapString, defaultTextColumns);
            dialog.addMessage("  note: leave empty to skip mapping, format is a=b,c=d");
            dialog.addCheckbox("Complete Stack After Export", completeStackAfterExport);

            dialog.showDialog();

            final boolean wasCancelled = dialog.wasCanceled();

            if (! wasCancelled) {
                baseDataUrl = dialog.getNextString().trim();
                basisRenderOwner = dialog.getNextString().trim();
                basisRenderProject = dialog.getNextString().trim();
                basisRenderStack =  dialog.getNextString().trim();
                trakMinZ = dialog.getNextNumber();
                trakMaxZ = dialog.getNextNumber();
                targetRenderOwner = dialog.getNextString().trim();
                targetRenderProject = dialog.getNextString().trim();
                targetRenderStack =  dialog.getNextString().trim();
                trakZToTargetZMapString =  dialog.getNextString().trim();
                completeStackAfterExport = dialog.getNextBoolean();

                sectionIdToDataMap.clear();
                trakZToTargetZMap.clear();
            }

            return wasCancelled;
        }

        boolean loadRenderData() {

            boolean wasCancelled = setParametersFromDialog();

            if (! wasCancelled) {

                try {
                    if (hasMappedZValues()) {
                        try {
                            for (final String zPair : trakZToTargetZMapString.split(",")) {
                                final String[] zPairArray = zPair.split("=");
                                trakZToTargetZMap.put(new Double(zPairArray[0]), new Double(zPairArray[1]));
                            }
                        } catch (final Throwable t) {
                            throw new IllegalArgumentException(
                                    "invalid z map string '" + trakZToTargetZMapString + "'", t);
                        }
                    }

                    final StackId targetStackId = new StackId(targetRenderOwner, targetRenderProject, targetRenderStack);
                    targetRenderClient = new RenderDataClient(baseDataUrl,
                                                              targetStackId.getOwner(),
                                                              targetStackId.getProject());

                    final StackId basisStackId = new StackId(basisRenderOwner, basisRenderProject, basisRenderStack);
                    basisRenderClient = new RenderDataClient(baseDataUrl,
                                                             basisStackId.getOwner(),
                                                             basisStackId.getProject());
                    final StackMetaData basisStackMetaData = basisRenderClient.getStackMetaData(basisRenderStack);
                    targetRenderClient.setupDerivedStack(basisStackMetaData, targetRenderStack);

                    for (final SectionData sectionData : basisRenderClient.getStackSectionData(basisRenderStack,
                                                                                               null,
                                                                                               null)) {
                        sectionIdToDataMap.put(sectionData.getSectionId(), sectionData);
                    }

                } catch (final Exception e) {
                    e.printStackTrace(System.out);
                    IJ.error("Invalid Render Parameters",
                             "Specified parameters caused the following exception:\n" + e.getMessage());
                    wasCancelled = loadRenderData();
                }
            }

            return wasCancelled;
        }

        boolean hasMappedZValues() {
             return trakZToTargetZMapString.length() > 0;
        }

        Double getRenderStackZ(final Double forTrakZ) {
             Double renderZ = trakZToTargetZMap.get(forTrakZ);
             if (renderZ == null) {
                 renderZ = forTrakZ;
             }
             return renderZ;
        }
    }

    private static final Pattern TILE_SECTION_ID_PATTERN = Pattern.compile(".*\\.(\\d+\\.\\d+)");
}