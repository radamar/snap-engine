package org.esa.beam.jai;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import javax.media.jai.ImageLayout;
import javax.media.jai.Interpolation;
import javax.media.jai.JAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.ROI;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.BandMergeDescriptor;
import javax.media.jai.operator.ConstantDescriptor;
import javax.media.jai.operator.ExpDescriptor;
import javax.media.jai.operator.ExtremaDescriptor;
import javax.media.jai.operator.FormatDescriptor;
import javax.media.jai.operator.HistogramDescriptor;
import javax.media.jai.operator.InvertDescriptor;
import javax.media.jai.operator.MaxDescriptor;
import javax.media.jai.operator.MinDescriptor;
import javax.media.jai.operator.RescaleDescriptor;
import javax.media.jai.operator.ScaleDescriptor;

import org.esa.beam.framework.datamodel.AbstractBand;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.ColorPaletteDef;
import org.esa.beam.framework.datamodel.ImageInfo;
import org.esa.beam.framework.datamodel.IndexCoding;
import org.esa.beam.framework.datamodel.MetadataAttribute;
import org.esa.beam.framework.datamodel.PinDescriptor;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.datamodel.ROIDefinition;
import org.esa.beam.framework.datamodel.RasterDataNode;
import org.esa.beam.framework.datamodel.TiePointGrid;
import org.esa.beam.framework.datamodel.VirtualBand;
import org.esa.beam.framework.draw.Figure;
import org.esa.beam.util.Debug;
import org.esa.beam.util.ImageUtils;
import org.esa.beam.util.IntMap;
import org.esa.beam.util.StringUtils;
import org.esa.beam.util.jai.JAIUtils;
import org.esa.beam.util.math.Histogram;

import com.bc.ceres.core.Assert;
import com.bc.ceres.glevel.LRImageFactory;
import com.bc.ceres.glevel.MRImage;
import com.bc.ceres.glevel.support.MRImageImpl;


public class ImageManager {
    private final static int MAX_PIXEL_COUNT = 256 * 256;
    private static final ColorPaletteDef.Point OTHER_POINT = new ColorPaletteDef.Point(Double.NaN, Color.BLACK, "Other");

    private final static ImageManager INSTANCE = new ImageManager();
    private final Map<Object, MRImage> maskImageMap = new WeakHashMap<Object, MRImage>(101);

    public static ImageManager getInstance() {
        return INSTANCE;
    }

    public synchronized void dispose() {
        for (MRImage image : maskImageMap.values()) {
            image.dispose();
        }
        maskImageMap.clear();
    }

    public static ImageLayout createSingleBandedImageLayout(int dataType,
                                                            int width,
                                                            int height,
                                                            int tileWidth,
                                                            int tileHeight) {
        SampleModel sampleModel = ImageUtils.createSingleBandedSampleModel(dataType, width, height);
        ColorModel colorModel = PlanarImage.createColorModel(sampleModel);
        return new ImageLayout(0, 0,
                               width,
                               height,
                               0, 0,
                               tileWidth,
                               tileHeight,
                               sampleModel,
                               colorModel);
    }

    public static ImageLayout createSingleBandedImageLayout(int dataBufferType,
                                                            int sourceWidth,
                                                            int sourceHeight,
                                                            Dimension tileSize,
                                                            int level) {
        if (sourceWidth < 0) {
            throw new IllegalArgumentException("sourceWidth");
        }
        if (sourceHeight < 0) {
            throw new IllegalArgumentException("sourceHeight");
        }
        if (level < 0) {
            throw new IllegalArgumentException("level");
        }
        final double scale = computeScale(level);
        int destWidth = (int) Math.floor(scale * sourceWidth);
        int destHeight = (int) Math.floor(scale * sourceHeight);
        SampleModel sampleModel = ImageUtils.createSingleBandedSampleModel(dataBufferType,
                                                                           destWidth,
                                                                           destHeight);
        ColorModel colorModel = PlanarImage.createColorModel(sampleModel);
        tileSize = tileSize != null ? tileSize : JAIUtils.computePreferredTileSize(destWidth, destHeight, 1);
        return new ImageLayout(0, 0,
                               destWidth,
                               destHeight,
                               0, 0,
                               tileSize.width,
                               tileSize.height,
                               sampleModel,
                               colorModel);
    }

    public static double computeScale(int level) {
        return Math.pow(2, -level);
    }

    // todo (mp,se: 2008/09/03)- is this a good place for this method? ; see also getProductDataType
    public static int getDataBufferType(int productDataType) {
        switch (productDataType) {
            case ProductData.TYPE_INT8:
            case ProductData.TYPE_UINT8:
                return DataBuffer.TYPE_BYTE;
            case ProductData.TYPE_INT16:
                return DataBuffer.TYPE_SHORT;
            case ProductData.TYPE_UINT16:
                return DataBuffer.TYPE_USHORT;
            case ProductData.TYPE_INT32:
            case ProductData.TYPE_UINT32:
                return DataBuffer.TYPE_INT;
            case ProductData.TYPE_FLOAT32:
                return DataBuffer.TYPE_FLOAT;
            case ProductData.TYPE_FLOAT64:
                return DataBuffer.TYPE_DOUBLE;
            default:
                throw new IllegalArgumentException("productDataType");
        }
    }

    // todo (mp,se: 2008/09/03)- is this a good place for this method? ; see also getDataBufferType
    public static int getProductDataType(int dataBufferType) {
        switch (dataBufferType) {
            case DataBuffer.TYPE_BYTE:
               return ProductData.TYPE_INT8;
            case DataBuffer.TYPE_SHORT:
                return ProductData.TYPE_INT16;
            case DataBuffer.TYPE_USHORT:
                return ProductData.TYPE_UINT16;
            case DataBuffer.TYPE_INT:
                return ProductData.TYPE_INT32;
            case DataBuffer.TYPE_FLOAT:
                return ProductData.TYPE_FLOAT32;
            case DataBuffer.TYPE_DOUBLE:
                return ProductData.TYPE_FLOAT64;
            default:
                throw new IllegalArgumentException("dataBufferType");
        }
    }

    public static PlanarImage createGeophysicalSourceImage(RasterDataNode rdn) {
        PlanarImage image = PlanarImage.wrapRenderedImage(rdn.getSourceImage());
        if (!rdn.isScalingApplied()) {
            return image;
        } else if (!rdn.isLog10Scaled()) {
            image = toDouble(image);
            image = rescale(image, rdn.getScalingFactor(), rdn.getScalingOffset());
        } else {
            image = toDouble(image);
            image = rescale(image, Math.log(10) * rdn.getScalingFactor(), Math.log(10) * rdn.getScalingOffset());
            image = ExpDescriptor.create(image, null);
        }
        return image;
    }

    private static PlanarImage rescale(PlanarImage image, double factor, double offset) {
        image = RescaleDescriptor.create(image,
                                         new double[]{factor},
                                         new double[]{offset}, null);
        return image;
    }

    private static PlanarImage toDouble(PlanarImage image) {
        final int dataType = image.getSampleModel().getDataType();
        if (dataType == DataBuffer.TYPE_FLOAT || dataType == DataBuffer.TYPE_DOUBLE) {
            return image;
        }
        return FormatDescriptor.create(image, DataBuffer.TYPE_FLOAT, null);
    }

    private static class MaskKey {
        final Product product; // todo - may cause memory leaks!!! Use WeakReference?
        final String expression;

        private MaskKey(Product product, String expression) {
            Assert.notNull(product, "product");
            Assert.notNull(expression, "expression");
            this.product = product;
            this.expression = expression;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null) {
                return false;
            }
            if (getClass() != o.getClass()) {
                return false;
            }
            MaskKey key = (MaskKey) o;
            return product == key.product && expression.equals(key.expression);

        }

        @Override
        public int hashCode() {
            int result;
            result = product.hashCode();
            result = 31 * result + expression.hashCode();
            return result;
        }
    }

    public PlanarImage createRgbImage(final RasterDataNode[] rasterDataNodes,
                                      final int level, int levelCount) {
        Assert.notNull(rasterDataNodes,
                       "rasterDataNodes");
        Assert.state(rasterDataNodes.length == 1 || rasterDataNodes.length == 3 || rasterDataNodes.length == 4,
                     "invalid number of bands");


        PlanarImage image;

        PlanarImage[] bandImages = getBandImages(rasterDataNodes, level);
        PlanarImage[] validMaskImages = getValidMaskImages(rasterDataNodes, level);
        prepareImageInfos(rasterDataNodes, bandImages, validMaskImages, levelCount);

        PlanarImage[] sourceImages = new PlanarImage[rasterDataNodes.length];
        for (int i = 0; i < rasterDataNodes.length; i++) {
            final RasterDataNode raster = rasterDataNodes[i];
            PlanarImage planarImage = bandImages[i];
            ImageInfo imageInfo = raster.getImageInfo();
            final IndexCoding indexCoding = (raster instanceof Band) ? ((Band) raster).getIndexCoding() : null;
            final double minSample = imageInfo.getColorPaletteDef().getMinDisplaySample();
            final double maxSample = imageInfo.getColorPaletteDef().getMaxDisplaySample();
            Assert.notNull(imageInfo, "imageInfo");

            if (indexCoding != null) {
                final IntMap sampleColorIndexMap = new IntMap((int) minSample - 1, 4098);
                final ColorPaletteDef.Point[] points = imageInfo.getColorPaletteDef().getPoints();
                for (int colorIndex = 0; colorIndex < points.length; colorIndex++) {
                    sampleColorIndexMap.putValue((int) points[colorIndex].getSample(), colorIndex);
                }

                planarImage = JAIUtils.createIndexedImage(planarImage, sampleColorIndexMap,
                                                          raster.getImageInfo().getColorPaletteDef().getNumPoints() - 1);
            } else {
                final double newMin = raster.scaleInverse(minSample);
                final double newMax = raster.scaleInverse(maxSample);
                planarImage = JAIUtils.createRescaleOp(planarImage,
                                                       255.0 / (newMax - newMin),
                                                       255.0 * newMin / (newMin - newMax));
                planarImage = JAIUtils.createFormatOp(planarImage,
                                                      DataBuffer.TYPE_BYTE);
            }
            sourceImages[i] = planarImage;
        }

        image = performIndexToRgbConversion(rasterDataNodes, sourceImages, validMaskImages);


        return image;
    }

    public PlanarImage[] getBandImages(RasterDataNode[] rasterDataNodes, int level) {
        PlanarImage[] planarImages = new PlanarImage[rasterDataNodes.length];
        for (int i = 0; i < rasterDataNodes.length; i++) {
            RasterDataNode raster = rasterDataNodes[i];
            planarImages[i] = getBandImage(raster, level);
        }
        return planarImages;
    }


    public PlanarImage getBandImage(RasterDataNode rasterDataNode, int level) {
        RenderedImage levelZeroImage = rasterDataNode.getSourceImage();
        if (levelZeroImage == null) {
            if (rasterDataNode instanceof TiePointGrid) {
                final TiePointGrid tiePointGrid = (TiePointGrid) rasterDataNode;
                levelZeroImage = new MRImageImpl(new LRImageFactory() {
                    @Override
                    public RenderedImage createLRImage(int level) {
                        return new TiePointGridOpImage(tiePointGrid, level);
                    }});
            } else if (rasterDataNode instanceof VirtualBand) {
                final VirtualBand virtualBand = (VirtualBand) rasterDataNode;
                levelZeroImage = new MRImageImpl(new LRImageFactory() {
                    @Override
                    public RenderedImage createLRImage(int level) {
                        return new VirtualBandOpImage(new Product[]{virtualBand.getProduct()}, virtualBand.getExpression(), virtualBand.getDataType(), level);
                    }});
            } else if (rasterDataNode instanceof AbstractBand) {
                final AbstractBand band = (AbstractBand) rasterDataNode;
                levelZeroImage = new MRImageImpl(new LRImageFactory() {
                    @Override
                    public RenderedImage createLRImage(int level) {
                        return new BandOpImage(band, level);
                    }});
            } else {
                throw new IllegalArgumentException("rasterDataNode: unknown subclass " + rasterDataNode.getClass());
            }
            rasterDataNode.setSourceImage(levelZeroImage);
        }
        return getDownscaledImage(levelZeroImage, level);
    }

    public PlanarImage getGeophysicalBandImage(RasterDataNode rasterDataNode, int level) {
        final PlanarImage image = getBandImage(rasterDataNode, level);
return image; // TODO!!!!       
//        RenderedImage levelZeroImage = null; //= rasterDataNode.getGeophysicalSourceImage();
//        if (levelZeroImage == null) {
//            levelZeroImage = createGeophysicalSourceImage(rasterDataNode);
////            rasterDataNode.setGeophysicalSourceImage(levelZeroImage);
//        }
//        return getDownscaledImage(levelZeroImage, level);
    }

    private PlanarImage getDownscaledImage(RenderedImage levelZeroImage, int level) {
        RenderedImage image;
        if (level == 0) {
            image = levelZeroImage;
        } else if (levelZeroImage instanceof MRImage) {
            image = ((MRImage) levelZeroImage).getLRImage(level);
        } else {
            image = createDownscaledImage(levelZeroImage, level);
        }
        return PlanarImage.wrapRenderedImage(image);
    }

    private static PlanarImage createDownscaledImage(RenderedImage levelZeroImage, int level) {
        PlanarImage image;
        float scale = (float) Math.pow(2, -level);
        image = ScaleDescriptor.create(levelZeroImage,
                                       scale, scale, 0.0f, 0.0f,
                                       Interpolation.getInstance(Interpolation.INTERP_NEAREST), null);
        return image;
    }

    public SingleBandedOpImage getMaskImage(final Product product, final String expression, int level) {
        final Object key = new MaskKey(product, expression);
        synchronized (maskImageMap) {
            MRImage mrImage = maskImageMap.get(key);
            if (mrImage == null) {
                mrImage = new MRImageImpl(new LRImageFactory() {
                    @Override
                    public RenderedImage createLRImage(int level) {
                        return MaskOpImage.create(product, expression, level);
                    }});
                maskImageMap.put(key, mrImage);
            }
             // Note: cast is ok, because interface of MRImage requires to return same type
            return (SingleBandedOpImage) mrImage.getLRImage(level);
        }
    }

    public PlanarImage getValidMaskImage(final RasterDataNode rasterDataNode, int level) {
        final String expression = rasterDataNode.getValidMaskExpression();
        if (expression == null || expression.isEmpty()) {
            return null;
        }
        RenderedImage levelZeroImage = rasterDataNode.getValidMaskImage();
        if (levelZeroImage == null) {
            levelZeroImage = new MRImageImpl(new LRImageFactory() {
                @Override
                public RenderedImage createLRImage(int level) {
                    return MaskOpImage.create(rasterDataNode, level);
                }});
            rasterDataNode.setValidMaskImage(levelZeroImage);
        }
        return getDownscaledImage(levelZeroImage, level);
    }

    public PlanarImage[] getValidMaskImages(RasterDataNode[] rasterDataNodes, int level) {
        final PlanarImage[] images = new PlanarImage[rasterDataNodes.length];
        for (int i = 0; i < rasterDataNodes.length; i++) {
            images[i] = getValidMaskImage(rasterDataNodes[i], level);
        }
        return images;
    }

    public void prepareImageInfos(RasterDataNode[] rasterDataNodes, int levelCount) {
        PlanarImage[] bandImages = getBandImages(rasterDataNodes, 0);
        PlanarImage[] validMaskImages = getValidMaskImages(rasterDataNodes, 0);
        prepareImageInfos(rasterDataNodes, bandImages, validMaskImages, levelCount);
    }

    private void prepareImageInfos(RasterDataNode[] rasterDataNodes,
                                   PlanarImage[] bandImages,
                                   PlanarImage[] validMaskImages, int levelCount) {

        for (int i = 0; i < rasterDataNodes.length; i++) {
            RasterDataNode raster = rasterDataNodes[i];

            PlanarImage bandImage = bandImages[i];
            PlanarImage validMaskImage = validMaskImages[i];
            ImageInfo imageInfo = raster.getImageInfo();
            if (imageInfo == null) {
                if (raster instanceof Band) {
                    final IndexCoding indexCoding = ((Band) raster).getIndexCoding();
                    if (indexCoding != null) {
                        imageInfo = createIndexedImageInfo(indexCoding);
                    }
                }
                if (imageInfo == null) {
                    final PlanarImage statisticsBandImage;
                    final PlanarImage statisticsValidMaskImage;
                    if (bandImage.getWidth() * bandImage.getHeight() <= MAX_PIXEL_COUNT) {
                        statisticsBandImage = bandImage;
                        statisticsValidMaskImage = validMaskImage;
                    } else {
                        final int statisticsLevel = levelCount - 1;
                        statisticsBandImage = getBandImage(raster, statisticsLevel);
                        statisticsValidMaskImage = validMaskImage != null ? getValidMaskImage(raster, statisticsLevel) : null;
                    }

                    ROI roi = createROI(statisticsValidMaskImage);

                    System.out.println("Computing sample extrema for " + raster.getName() + "...");
                    final RenderedOp extremaOp = ExtremaDescriptor.create(statisticsBandImage, roi, 1, 1, false, 1, null);

                    final long t0 = System.nanoTime();
                    final double[][] extrema = (double[][]) extremaOp.getProperty("extrema");
                    final double min = extrema[0][0];
                    final double max = extrema[1][0];
                    final long t1 = System.nanoTime();

                    System.out.println("Image Info:");
                    System.out.println("  name       = " + raster.getName());
                    System.out.println("  width      = " + bandImage.getWidth());
                    System.out.println("  height     = " + bandImage.getHeight());
                    System.out.println("  tileWidth  = " + bandImage.getTileWidth());
                    System.out.println("  tileHeight = " + bandImage.getTileHeight());

                    System.out.printf("Extrema computed in %f ms:\n", (t1 - t0) / (1000.0 * 1000.0));
                    System.out.println("  width  = " + statisticsBandImage.getWidth());
                    System.out.println("  height = " + statisticsBandImage.getHeight());
//                    System.out.println("  level  = " + statisticsBandImage.getLevel());
                    System.out.println("  min    = " + min);
                    System.out.println("  max    = " + max);

                    Debug.trace("Sample extrema computed.");

                    if (min < max) {
                        Debug.trace("Computing sample frequencies for [" + raster.getName() + "]...");
                        final RenderedOp histogramOp = HistogramDescriptor.create(statisticsBandImage, roi, 1, 1, new int[]{256}, extrema[0], extrema[1], null);
                        Histogram histogram = getBeamHistogram(histogramOp);
                        imageInfo = raster.createDefaultImageInfo(null, histogram);
                        raster.setImageInfo(imageInfo);
                        Debug.trace("Sample frequencies computed.");
                    } else {
                        raster.setImageInfo(null);
                    }
                }
            }
        }
    }

    private static ROI createROI(PlanarImage maskOpImage) {
        if (maskOpImage == null) {
            return null;
        }
        return new ROI(maskOpImage);
    }

    public static int computeMaxLevelCount(int width, int height) {
        int level = 1;
        float scale = 1.0f;
        while ((scale * width) * (scale * height) >= MAX_PIXEL_COUNT) {
            level++;
            scale *= 0.5f;
        }
        return level;
    }

    private static ImageInfo createIndexedImageInfo(IndexCoding indexCoding) {
        final MetadataAttribute[] attributes = indexCoding.getAttributes();
        IntMap sampleToIndexMap = new IntMap();
        int sampleMin = Integer.MAX_VALUE;
        int sampleMax = Integer.MIN_VALUE;
        // Note: we need one colour more, the last element is the "Other" class
        final ColorPaletteDef.Point[] points = new ColorPaletteDef.Point[attributes.length + 1];
        for (int index = 0; index < attributes.length; index++) {
            MetadataAttribute attribute = attributes[index];
            final int sample = attribute.getData().getElemInt();
            sampleMin = Math.min(sampleMin, sample);
            sampleMax = Math.max(sampleMax, sample);
            sampleToIndexMap.putValue(sample, index);
            double t = (index + 1.0) / attributes.length;
            points[index] = new ColorPaletteDef.Point(sample,
                                                      new Color((float) (0.5 + 0.5 * Math.sin(Math.PI / 3. + t * 4. * Math.PI)),
                                                                (float) (0.5 + 0.5 * Math.sin(Math.PI / 2. + t * 2. * Math.PI)),
                                                                (float) (0.5 + 0.5 * Math.sin(Math.PI / 4. + t * 3. * Math.PI))),
                                                      attribute.getName());
        }
        points[points.length - 1] = OTHER_POINT;
        final ColorPaletteDef def = new ColorPaletteDef(points);
        return new ImageInfo(def);
    }

    public static PlanarImage performIndexToRgbConversion(RasterDataNode[] rasterDataNodes,
                                                          PlanarImage[] sourceImages,
                                                          PlanarImage[] maskOpImages) {
        PlanarImage image;
        if (rasterDataNodes.length == 1) {
            final Color[] palette = rasterDataNodes[0].getImageInfo().getColorPaletteDef().createColorPalette(rasterDataNodes[0]);
            final byte[][] lutData = new byte[3][palette.length];
            for (int i = 0; i < palette.length; i++) {
                lutData[0][i] = (byte) palette[i].getRed();
                lutData[1][i] = (byte) palette[i].getGreen();
                lutData[2][i] = (byte) palette[i].getBlue();
            }
            image = JAIUtils.createLookupOp(sourceImages[0], lutData);
            if (maskOpImages[0] != null) {
                image = BandMergeDescriptor.create(image, maskOpImages[0], null);
            }
        } else {
            PlanarImage alpha = null;
            for (PlanarImage maskOpImage : maskOpImages) {
                if (maskOpImage != null) {
                    if (alpha != null) {
                        alpha = MaxDescriptor.create(alpha, maskOpImage, null);
                    } else {
                        alpha = maskOpImage;
                    }
                }
            }
            image = BandMergeDescriptor.create(sourceImages[0], sourceImages[1], null);
            image = BandMergeDescriptor.create(image, sourceImages[2], null);
            if (alpha != null) {
                image = BandMergeDescriptor.create(image, alpha, null);
            }
        }
        return image;
    }

    private static Histogram getBeamHistogram(RenderedOp histogramImage) {
        javax.media.jai.Histogram jaiHistogram = JAIUtils.getHistogramOf(histogramImage);

        int[] bins = jaiHistogram.getBins(0);
        int minIndex = 0;
        int maxIndex = bins.length - 1;
        for (int i = 0; i < bins.length; i++) {
            if (bins[i] > 0) {
                minIndex = i;
                break;
            }
        }
        for (int i = bins.length - 1; i >= 0; i--) {
            if (bins[i] > 0) {
                maxIndex = i;
                break;
            }
        }
        double lowValue = jaiHistogram.getLowValue(0);
        double highValue = jaiHistogram.getHighValue(0);
        int numBins = jaiHistogram.getNumBins(0);
        double binWidth = (highValue - lowValue) / numBins;
        int[] croppedBins = new int[maxIndex - minIndex + 1];
        System.arraycopy(bins, minIndex, croppedBins, 0, croppedBins.length);
        return new Histogram(croppedBins,
                             lowValue + minIndex * binWidth,
                             lowValue + (maxIndex + 1.0) * binWidth);
    }

    public PlanarImage createColoredMaskImage(Product product, String expression, Color color, boolean invertMask, int level) {
        PlanarImage image = getMaskImage(product, expression, level);
        return createColoredMaskImage(color, image, invertMask);
    }

    public static PlanarImage createColoredMaskImage(Color color, PlanarImage alphaImage, boolean invertAlpha) {
        return createColoredMaskImage(color, invertAlpha ? InvertDescriptor.create(alphaImage, null) : alphaImage);
    }

    public static PlanarImage createColoredMaskImage(Color color, PlanarImage alphaImage) {
        final ImageLayout imageLayout = new ImageLayout();
        imageLayout.setTileWidth(alphaImage.getTileWidth());
        imageLayout.setTileHeight(alphaImage.getTileHeight());
        final RenderingHints hints = new RenderingHints(JAI.KEY_IMAGE_LAYOUT, imageLayout);
        final RenderedOp colorImage = ConstantDescriptor.create((float) alphaImage.getWidth(), (float) alphaImage.getHeight(), new Byte[]{
                (byte) color.getRed(),
                (byte) color.getGreen(),
                (byte) color.getBlue(),
        }, hints);
        return BandMergeDescriptor.create(colorImage, alphaImage, hints);
    }

    /**
     * Creates a colored ROI image for the given band.
     *
     * @param rasterDataNode the band
     * @param color          the color
     * @param level          the level
     * @return the image, or null if the band has no valid ROI definition
     */
    public PlanarImage createColoredRoiImage(RasterDataNode rasterDataNode, Color color, int level) {
        final PlanarImage roi = createRoiMaskImage(rasterDataNode, level);
        if (roi == null) {
            return null;
        }
        return createColoredMaskImage(color, roi, false);
    }

    /**
     * Creates a ROI for the given band.
     *
     * @param rasterDataNode the band
     * @param level          the level
     * @return the ROI, or null if the band has no valid ROI definition
     */
    public PlanarImage createRoiMaskImage(final RasterDataNode rasterDataNode, int level) {
        final ROIDefinition roiDefinition = rasterDataNode.getROIDefinition();
        if (roiDefinition == null) {
            return null;
        }

        ArrayList<PlanarImage> rois = new ArrayList<PlanarImage>(4);

        // Step 1:  insert ROI pixels determined by bitmask expression
        String bitmaskExpr = roiDefinition.getBitmaskExpr();
        if (!StringUtils.isNullOrEmpty(bitmaskExpr) && roiDefinition.isBitmaskEnabled()) {
            rois.add(getMaskImage(rasterDataNode.getProduct(), bitmaskExpr, level));
        }

        // Step 2:  insert ROI pixels within value range
        if (roiDefinition.isValueRangeEnabled()) {
            String rangeExpr = rasterDataNode.getName() + " >= " + roiDefinition.getValueRangeMin() + " && "
                    + rasterDataNode.getName() + " <= " + roiDefinition.getValueRangeMax();
            rois.add(getMaskImage(rasterDataNode.getProduct(), rangeExpr, level));
        }

        // Step 3:  insert ROI pixels for pins
        if (roiDefinition.isPinUseEnabled() && rasterDataNode.getProduct().getPinGroup().getNodeCount() > 0) {

            final Object key = new MaskKey(rasterDataNode.getProduct(), rasterDataNode.getName() + "_RoiPlacemarks");
            MRImage placemarkMaskMRImage = null;
            synchronized (maskImageMap) {
                placemarkMaskMRImage = maskImageMap.get(key);
                if (placemarkMaskMRImage == null) {
                    placemarkMaskMRImage = new MRImageImpl(new LRImageFactory() {
                        @Override
                        public RenderedImage createLRImage(int level) {
                            return new PlacemarkMaskOpImage(rasterDataNode.getProduct(), PinDescriptor.INSTANCE, 3,
                                    rasterDataNode.getSceneRasterWidth(),
                                    rasterDataNode.getSceneRasterHeight(), level);
                        }});
                    maskImageMap.put(key, placemarkMaskMRImage);
                }
            }
            rois.add((PlanarImage) placemarkMaskMRImage.getLRImage(level));
        }

        // Step 4:  insert ROI pixels within shape
        Figure roiShapeFigure = roiDefinition.getShapeFigure();
        if (roiDefinition.isShapeEnabled() && roiShapeFigure != null) {

            final Object key = new MaskKey(rasterDataNode.getProduct(), rasterDataNode.getName() + "_RoiShapes");
            MRImage shapeMaskMRImage = null;
            synchronized (maskImageMap) {
                shapeMaskMRImage = maskImageMap.get(key);
                if (shapeMaskMRImage == null) {
                    final Shape roiShape = roiShapeFigure.getShape();
                    shapeMaskMRImage = new MRImageImpl(new LRImageFactory() {
                        @Override
                        public RenderedImage createLRImage(int level) {
                            return new ShapeMaskOpImage(roiShape,
                                    rasterDataNode.getSceneRasterWidth(),
                                    rasterDataNode.getSceneRasterHeight(),
                                    level);
                        }});
                    maskImageMap.put(key, shapeMaskMRImage);
                }
            }
            rois.add((PlanarImage) shapeMaskMRImage.getLRImage(level));
        }

        if (rois.size() == 0) {
            // todo - null is returned whenever a shape is converted into a ROI for any but the first time
            // todo - may be this problem is due to concurrency issues
            return null;
        }

        PlanarImage roi = rois.get(0);

        // Step 5: combine ROIs
        for (int i = 1; i < rois.size(); i++) {
            PlanarImage roi2 = rois.get(i);
            if (roiDefinition.isOrCombined()) {
                roi = MaxDescriptor.create(roi, roi2, null);
            } else {
                roi = MinDescriptor.create(roi, roi2, null);
            }
        }

        // Step 6:  invert ROI pixels
        if (roiDefinition.isInverted()) {
            roi = InvertDescriptor.create(roi, null);
        }

        return roi;
    }
}
