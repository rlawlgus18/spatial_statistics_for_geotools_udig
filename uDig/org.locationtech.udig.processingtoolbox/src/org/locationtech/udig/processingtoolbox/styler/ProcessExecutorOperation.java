/*
 * uDig - User Friendly Desktop Internet GIS client
 * (C) MangoSystem - www.mangosystem.com 
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * (http://www.eclipse.org/legal/epl-v10.html), and the HydroloGIS BSD
 * License v1.0 (http://udig.refractions.net/files/hsd3-v10.html).
 */
package org.locationtech.udig.processingtoolbox.styler;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FilenameUtils;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.data.DataUtilities;
import org.geotools.data.Parameter;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.geometry.jts.JTS;
import org.geotools.styling.Style;
import org.geotools.util.logging.Logging;
import org.locationtech.udig.catalog.CatalogPlugin;
import org.locationtech.udig.catalog.ICatalog;
import org.locationtech.udig.catalog.IGeoResource;
import org.locationtech.udig.catalog.IService;
import org.locationtech.udig.catalog.util.GeoToolsAdapters;
import org.locationtech.udig.processingtoolbox.ToolboxPlugin;
import org.locationtech.udig.processingtoolbox.common.DataStoreFactory;
import org.locationtech.udig.processingtoolbox.common.FeatureTypes;
import org.locationtech.udig.processingtoolbox.common.FeatureTypes.SimpleShapeType;
import org.locationtech.udig.processingtoolbox.common.FormatUtils;
import org.locationtech.udig.processingtoolbox.common.RasterSaveAsOp;
import org.locationtech.udig.processingtoolbox.common.ShapeExportOp;
import org.locationtech.udig.project.IMap;
import org.locationtech.udig.project.internal.Layer;
import org.locationtech.udig.project.ui.ApplicationGIS;
import org.locationtech.udig.style.sld.SLDContent;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.geometry.BoundingBox;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.util.ProgressListener;

import com.vividsolutions.jts.geom.Geometry;

/**
 * ProcessExecutorOperation
 * 
 * @author MapPlus
 * 
 */
public class ProcessExecutorOperation implements IRunnableWithProgress {
    protected static final Logger LOGGER = Logging.getLogger(ProcessExecutorOperation.class);

    private IMap map;

    private org.geotools.process.ProcessFactory factory;

    private org.opengis.feature.type.Name processName;

    private Map<String, Object> inputParams = new HashMap<String, Object>();

    private Map<String, Object> outputParams = new HashMap<String, Object>();

    private String windowTitle;

    private StringBuffer outputBuffer = new StringBuffer();

    public ProcessExecutorOperation(IMap map, org.geotools.process.ProcessFactory factory,
            org.opengis.feature.type.Name processName, Map<String, Object> inputParams,
            Map<String, Object> outputParams) {
        this.map = map;
        this.factory = factory;
        this.processName = processName;
        this.inputParams = inputParams;
        this.outputParams = outputParams;
        this.windowTitle = factory.getTitle(processName).toString();
    }

    public String getOutputText() {
        return outputBuffer.toString();
    }

    @Override
    public void run(IProgressMonitor monitor) throws InvocationTargetException,
            InterruptedException {
        int increment = 10;
        monitor.beginTask("Running operation...", 100); //$NON-NLS-1$
        monitor.worked(increment);

        try {
            monitor.setTaskName("Running operation..."); //$NON-NLS-1$
            ToolboxPlugin.log("Executing " + windowTitle + "..."); //$NON-NLS-1$ //$NON-NLS-2$

            ProgressListener subMonitor = GeoToolsAdapters.progress(SubMonitor.convert(monitor,
                    "Internal Processing... ", 60)); //$NON-NLS-1$

            org.geotools.process.Process process = factory.create(processName);
            final Map<String, Object> result = process.execute(inputParams, subMonitor);
            monitor.worked(increment);

            monitor.setTaskName("Adding layer..."); //$NON-NLS-1$
            outputBuffer.setLength(0);
            if (result != null) {
                Map<String, Parameter<?>> resultInfo = factory.getResultInfo(processName, null);
                for (Entry<String, Object> entrySet : result.entrySet()) {
                    final Object val = entrySet.getValue();
                    if (val == null) {
                        continue;
                    }

                    ToolboxPlugin.log("Writing result..."); //$NON-NLS-1$
                    if (val instanceof SimpleFeatureCollection) {
                        postProcessing((SimpleFeatureCollection) val,
                                outputParams.get(entrySet.getKey()),
                                resultInfo.get(entrySet.getKey()).metadata, monitor);
                    } else if (val instanceof Geometry) {
                        postProcessing(geometryToFeatures((Geometry) val, processName.toString()),
                                outputParams.get(entrySet.getKey()),
                                resultInfo.get(entrySet.getKey()).metadata, monitor);
                    } else if (val instanceof BoundingBox) {
                        Geometry boundingBox = JTS.toGeometry((BoundingBox) val);
                        boundingBox.setUserData(((BoundingBox) val).getCoordinateReferenceSystem());
                        postProcessing(geometryToFeatures(boundingBox, processName.toString()),
                                outputParams.get(entrySet.getKey()),
                                resultInfo.get(entrySet.getKey()).metadata, monitor);
                    } else if (val instanceof GridCoverage2D) {
                        File outputFile = new File(outputParams.get(entrySet.getKey()).toString());
                        RasterSaveAsOp saveAs = new RasterSaveAsOp();
                        GridCoverage2D output = saveAs.saveAsGeoTiff((GridCoverage2D) val,
                                outputFile.getAbsolutePath());
                        ToolboxPlugin.log("Adding layer..."); //$NON-NLS-1$
                        MapUtils.addGridCoverageToMap(map, output, outputFile, null);
                    } else if (Number.class.isAssignableFrom(val.getClass())) {
                        outputBuffer.append(FormatUtils.format(Double.parseDouble(val.toString())));
                        outputBuffer.append(System.getProperty("line.separator")); //$NON-NLS-1$
                    } else {
                        outputBuffer.append(val.toString());
                        outputBuffer.append(System.getProperty("line.separator")); //$NON-NLS-1$
                    }
                    monitor.worked(increment);
                }
            }
            monitor.worked(increment);
        } catch (Exception e) {
            ToolboxPlugin.log(e.getMessage());
        } finally {
            ToolboxPlugin.log("Completed " + windowTitle + "..."); //$NON-NLS-1$ //$NON-NLS-2$
            monitor.done();
        }
    }

    @SuppressWarnings("nls")
    private void postProcessing(SimpleFeatureCollection source, Object outputPath,
            Map<String, Object> outputMeta, IProgressMonitor monitor) {
        File filePath = new File(outputPath.toString());

        monitor.setTaskName("Writing result..."); //$NON-NLS-1$
        String typeName = FilenameUtils.removeExtension(FilenameUtils.getName(filePath.getPath()));
        SimpleFeatureSource featureSource = null;
        try {
            ShapeExportOp exportOp = ShapeExportOp.getDefault();
            exportOp.setOutputDataStore(DataStoreFactory.getShapefileDataStore(
                    filePath.getParent(), false));
            exportOp.setOutputTypeName(typeName);
            featureSource = exportOp.execute(source);
        } catch (IOException e) {
            ToolboxPlugin.log(e.getMessage());
        }

        if (featureSource == null) {
            return;
        }

        monitor.setTaskName("Adding layer..."); //$NON-NLS-1$
        ToolboxPlugin.log("Adding layer..."); //$NON-NLS-1$

        SimpleFeatureType schema = source.getSchema();
        SSStyleBuilder ssBuilder = new SSStyleBuilder(schema);
        ssBuilder.setOpacity(0.8f);

        Style style = ssBuilder.getDefaultFeatureStyle();
        if (outputMeta.containsKey(Parameter.OPTIONS)) {
            // new KVP(Parameter.OPTIONS, "렌더러유형.필드명")
            // 렌더러유형 = LISA, UniqueValues, ClassBreaks, Density, Distance, Interpolation
            // ClassBreaks = EqualInterval, Quantile, NaturalBreaks, StdDev
            // Point, LineString이 ClassBreaks일 경우 크기도 함께 설정

            try {
                String value = outputMeta.get(Parameter.OPTIONS).toString();
                String[] splits = value.split("\\."); //$NON-NLS-1$ 
                String styleName = splits[0].toUpperCase();

                String functionName = null;
                if (styleName.startsWith("LISA")) {
                    style = ssBuilder.getLISAStyle("COType");
                } else if (styleName.startsWith("CL") || styleName.startsWith("JE")
                        || styleName.startsWith("NA")) {
                    functionName = "JenksNaturalBreaksFunction";
                } else if (styleName.startsWith("E")) {
                    functionName = "EqualIntervalFunction";
                } else if (styleName.startsWith("S")) {
                    functionName = "StandardDeviationFunction";
                } else if (styleName.startsWith("Q")) {
                    functionName = "QuantileFunction";
                }

                if (functionName != null && splits.length == 2) {
                    String fieldName = splits[1]; // inputParams
                    if (schema.indexOf(fieldName) == -1) {
                        fieldName = inputParams.get(fieldName).toString();
                    }

                    if (schema.indexOf(fieldName) != -1) {
                        Class<?> binding = schema.getDescriptor(fieldName).getType().getBinding();
                        if (Number.class.isAssignableFrom(binding)) {
                            SimpleShapeType shapeType = FeatureTypes.getSimpleShapeType(source);
                            if (shapeType == SimpleShapeType.POINT) {
                                GraduatedSymbolStyleBuilder builder = new GraduatedSymbolStyleBuilder();
                                builder.setMethodName(functionName);
                                style = builder.createStyle(source, fieldName);
                            } else {
                                GraduatedColorStyleBuilder builder = new GraduatedColorStyleBuilder();
                                style = builder.createStyle(source, fieldName, functionName, 5,
                                        "OrRd");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                ToolboxPlugin.log(e.getMessage());
            }
        } else {
            if (source.getSchema().indexOf("COType") != -1) {
                style = ssBuilder.getLISAStyle("COType");
            } else if (source.getSchema().indexOf("GiZScore") != -1) {
                style = ssBuilder.getZScoreStdDevStyle("GiZScore");
            }
        }

        try {
            CatalogPlugin catalogPlugin = CatalogPlugin.getDefault();
            ICatalog localCatalog = catalogPlugin.getLocalCatalog();

            URL resourceId = DataUtilities.fileToURL(filePath);
            List<IService> services = catalogPlugin.getServiceFactory().createService(resourceId);
            for (IService service : services) {
                localCatalog.add(service);
                for (IGeoResource resource : service.resources(new NullProgressMonitor())) {
                    List<IGeoResource> resourceList = Collections.singletonList(resource);
                    final int pos = map.getMapLayers().size();
                    Layer layer = (Layer) ApplicationGIS.addLayersToMap(map, resourceList, pos)
                            .get(0);
                    layer.setName(typeName);
                    layer.setVisible(true);

                    if (style != null) {
                        // put the style on the blackboard
                        layer.getStyleBlackboard().put(SLDContent.ID, style);
                        layer.getStyleBlackboard().setSelected(new String[] { SLDContent.ID });
                    }

                    // refresh
                    layer.refresh(layer.getBounds(new NullProgressMonitor(), null));
                }
            }
        } catch (MalformedURLException e) {
            LOGGER.log(Level.FINER, e.getMessage(), e);
        } catch (IOException e) {
            LOGGER.log(Level.FINER, e.getMessage(), e);
        }
    }

    private SimpleFeatureCollection geometryToFeatures(Geometry source, String layerName) {
        CoordinateReferenceSystem crs = map.getViewportModel().getCRS();
        if (source.getUserData() != null
                && CoordinateReferenceSystem.class
                        .isAssignableFrom(source.getUserData().getClass())) {
            crs = (CoordinateReferenceSystem) source.getUserData();
        }

        SimpleFeatureType schema = FeatureTypes.getDefaultType(layerName, source.getClass(), crs);

        ListFeatureCollection features = new ListFeatureCollection(schema);
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(schema);

        SimpleFeature feature = builder.buildFeature(null);
        feature.setDefaultGeometry(source);
        features.add(feature);

        return features;
    }
}
