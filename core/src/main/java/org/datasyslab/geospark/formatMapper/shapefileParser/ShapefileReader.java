/*
 * FILE: ShapefileReader
 * Copyright (c) 2015 - 2018 GeoSpark Development Team
 *
 * MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */
package org.datasyslab.geospark.formatMapper.shapefileParser;

import com.vividsolutions.jts.geom.*;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.datasyslab.geospark.formatMapper.shapefileParser.boundary.BoundBox;
import org.datasyslab.geospark.formatMapper.shapefileParser.boundary.BoundaryInputFormat;
import org.datasyslab.geospark.formatMapper.shapefileParser.fieldname.FieldnameInputFormat;
import org.datasyslab.geospark.formatMapper.shapefileParser.shapes.PrimitiveShape;
import org.datasyslab.geospark.formatMapper.shapefileParser.shapes.ShapeInputFormat;
import org.datasyslab.geospark.formatMapper.shapefileParser.shapes.ShapeKey;
import org.datasyslab.geospark.spatialRDD.LineStringRDD;
import org.datasyslab.geospark.spatialRDD.PointRDD;
import org.datasyslab.geospark.spatialRDD.PolygonRDD;
import org.datasyslab.geospark.spatialRDD.SpatialRDD;
import scala.Tuple2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class ShapefileReader
{

    /**
     * read shapefile in inputPath with default GeometryFactory and return an RDD of Geometry.
     *
     * @param sc
     * @param inputPath
     * @return
     */
    public static SpatialRDD<Geometry> readToGeometryRDD(JavaSparkContext sc, String inputPath)
    {
        return readToGeometryRDD(sc, inputPath, new GeometryFactory());
    }

    /**
     * read shapefile in inputPath with customized GeometryFactory and return an RDD of Geometry.
     *
     * @param sc
     * @param inputPath
     * @param geometryFactory
     * @return
     */
    public static SpatialRDD<Geometry> readToGeometryRDD(JavaSparkContext sc, String inputPath, final GeometryFactory geometryFactory)
    {
        SpatialRDD<Geometry> spatialRDD = new SpatialRDD();
        spatialRDD.rawSpatialRDD = readShapefile(sc, inputPath, geometryFactory);
        try {
            spatialRDD.fieldNames = readFieldNames(sc, inputPath);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        return spatialRDD;
    }

    /**
     * read shapefiles in inputPath and return an RDD of Geometry.
     *
     * @param sc
     * @param inputPath
     * @param geometryFactory
     * @return
     */
    private static JavaRDD<Geometry> readShapefile(
            JavaSparkContext sc,
            String inputPath,
            final GeometryFactory geometryFactory
    )
    {
        JavaPairRDD<ShapeKey, PrimitiveShape> shapePrimitiveRdd = sc.newAPIHadoopFile(
                inputPath,
                ShapeInputFormat.class,
                ShapeKey.class,
                PrimitiveShape.class,
                sc.hadoopConfiguration()
        );
        return shapePrimitiveRdd.map(new Function<Tuple2<ShapeKey, PrimitiveShape>, Geometry>()
        {
            @Override
            public Geometry call(Tuple2<ShapeKey, PrimitiveShape> primitiveTuple)
                    throws Exception
            {
                // parse bytes to shape
                return primitiveTuple._2().getShape(geometryFactory);
            }
        });
    }

    /**
     *
     * Boundary logics
     *
     */

    /**
     * read and merge bound boxes of all shapefiles user input, if there is no, leave BoundBox null;
     */
    public static BoundBox readBoundBox(JavaSparkContext sc, String inputPath)
    {
        // read bound boxes into memory
        JavaPairRDD<Long, BoundBox> bounds = sc.newAPIHadoopFile(
                inputPath,
                BoundaryInputFormat.class,
                Long.class,
                BoundBox.class,
                sc.hadoopConfiguration()
        );
        // merge all into one
        bounds = bounds.reduceByKey(new Function2<BoundBox, BoundBox, BoundBox>()
        {
            @Override
            public BoundBox call(BoundBox box1, BoundBox box2)
                    throws Exception
            {
                return BoundBox.mergeBoundBox(box1, box2);
            }
        });
        // if there is a result assign it to variable : boundBox
        if (bounds.count() > 0) {
            return new BoundBox(bounds.collect().get(0)._2());
        }
        else { return null; }
    }

    /**
     *
     * @param sc Spark Context
     * @param inputPath folder which contains shape file with dbf metadata file
     * @return List of Strings if dbf file was found; return null if no dbf file
     * @throws IOException
     */

    public static List<String> readFieldNames(JavaSparkContext sc, String inputPath) throws IOException {
        // read bound boxes into memory
        JavaPairRDD<Long, String> fieldDescriptors = sc.newAPIHadoopFile(
                inputPath,
                FieldnameInputFormat.class,
                Long.class,
                String.class,
                sc.hadoopConfiguration()
        );
        // merge all into one
        fieldDescriptors = fieldDescriptors.reduceByKey(new Function2<String, String, String>()
        {
            @Override
            public String call(String descripter1, String descripter2)
                    throws Exception
            {
                return descripter1 + " "+ descripter2;
            }
        });
        // if there is a result assign it to variable : fieldNames
        List<String> result = Arrays.asList(fieldDescriptors.collect().get(0)._2().split("\t"));
        if (result.size()>1) {
            return result;
        }
        else if (result.size()==1) {
            // Sometimes the result has an empty string, we need to remove it
            if (result.get(0).equalsIgnoreCase("")) return null;
            return result;
        }
        else return null;
    }
    /**
     *
     * Read To SpatialRDD logics
     *
     */

    /**
     * read shapefile and return as an PolygonRDD
     *
     * @param sc
     * @param inputPath
     * @return
     */
    public static PolygonRDD readToPolygonRDD(JavaSparkContext sc, String inputPath)
    {
        return geometryToPolygon(readToGeometryRDD(sc, inputPath));
    }

    /**
     * read shapefile with customized GeometryFactory and return as an PolygonRDD
     *
     * @param sc
     * @param inputPath
     * @param geometryFactory
     * @return
     */
    public static PolygonRDD readToPolygonRDD(JavaSparkContext sc, String inputPath, final GeometryFactory geometryFactory)
    {
        return geometryToPolygon(readToGeometryRDD(sc, inputPath, geometryFactory));
    }

    /**
     * convert geometry rdd to
     *
     * @param geometryRDD
     * @return
     */
    public static PolygonRDD geometryToPolygon(SpatialRDD geometryRDD)
    {
        PolygonRDD polygonRDD = new PolygonRDD(geometryRDD.rawSpatialRDD.flatMap(new FlatMapFunction<Geometry, Polygon>()
        {
            @Override
            public Iterator<Polygon> call(Geometry spatialObject)
                    throws Exception
            {
                List<Polygon> result = new ArrayList<Polygon>();
                if (spatialObject instanceof MultiPolygon) {
                    MultiPolygon multiObjects = (MultiPolygon) spatialObject;
                    for (int i = 0; i < multiObjects.getNumGeometries(); i++) {
                        Polygon oneObject = (Polygon) multiObjects.getGeometryN(i);
                        oneObject.setUserData(multiObjects.getUserData());
                        result.add(oneObject);
                    }
                }
                else if (spatialObject instanceof Polygon) {
                    result.add((Polygon) spatialObject);
                }
                else {
                    throw new Exception("[ShapefileRDD][getPolygonRDD] the object type is not Polygon or MultiPolygon type. It is " + spatialObject.getGeometryType());
                }
                return result.iterator();
            }
        }));
        polygonRDD.fieldNames = geometryRDD.fieldNames;
        return polygonRDD;
    }

    /**
     * read shapefile and return as an PointRDD
     *
     * @param sc
     * @param inputPath
     * @return
     */
    public static PointRDD readToPointRDD(JavaSparkContext sc, String inputPath)
    {
        return geometryToPoint(readToGeometryRDD(sc, inputPath));
    }

    /**
     * read shapefile with customized GeometryFactory and return as an PointRDD
     *
     * @param sc
     * @param inputPath
     * @param geometryFactory
     * @return
     */
    public static PointRDD readToPointRDD(JavaSparkContext sc, String inputPath, final GeometryFactory geometryFactory)
    {
        return geometryToPoint(readToGeometryRDD(sc, inputPath, geometryFactory));
    }

    /**
     * convert geometry rdd to
     *
     * @param geometryRDD
     * @return
     */
    public static PointRDD geometryToPoint(SpatialRDD geometryRDD)
    {
        PointRDD pointRDD = new PointRDD(
                geometryRDD.rawSpatialRDD.flatMap(new FlatMapFunction<Geometry, Point>()
                {
                    @Override
                    public Iterator<Point> call(Geometry spatialObject)
                            throws Exception
                    {
                        List<Point> result = new ArrayList<Point>();
                        if (spatialObject instanceof MultiPoint) {
                            MultiPoint multiObjects = (MultiPoint) spatialObject;
                            for (int i = 0; i < multiObjects.getNumGeometries(); i++) {
                                Point oneObject = (Point) multiObjects.getGeometryN(i);
                                oneObject.setUserData(multiObjects.getUserData());
                                result.add(oneObject);
                            }
                        }
                        else if (spatialObject instanceof Point) {
                            result.add((Point) spatialObject);
                        }
                        else {
                            throw new Exception("[ShapefileRDD][getPointRDD] the object type is not Point or MultiPoint type. It is " + spatialObject.getGeometryType());
                        }
                        return result.iterator();
                    }
                })
        );
        pointRDD.fieldNames = geometryRDD.fieldNames;
        return pointRDD;
    }

    /**
     * read shapefile and return as an LineStringRDD
     *
     * @param sc
     * @param inputPath
     * @return
     */
    public static LineStringRDD readToLineStringRDD(JavaSparkContext sc, String inputPath)
    {
        return geometryToLineString(readToGeometryRDD(sc, inputPath));
    }

    /**
     * read shapefile with customized GeometryFactory and return as an LineStringRDD
     *
     * @param sc
     * @param inputPath
     * @param geometryFactory
     * @return
     */
    public static LineStringRDD readToLineStringRDD(JavaSparkContext sc, String inputPath, final GeometryFactory geometryFactory)
    {
        return geometryToLineString(readToGeometryRDD(sc, inputPath, geometryFactory));
    }

    /**
     * convert geometry rdd to
     *
     * @param geometryRDD
     * @return
     */
    public static LineStringRDD geometryToLineString(SpatialRDD geometryRDD)
    {
        LineStringRDD lineStringRDD = new LineStringRDD(
                geometryRDD.rawSpatialRDD.flatMap(new FlatMapFunction<Geometry, LineString>()
                {
                    @Override
                    public Iterator<LineString> call(Geometry spatialObject)
                            throws Exception
                    {
                        List<LineString> result = new ArrayList<LineString>();
                        if (spatialObject instanceof MultiLineString) {
                            MultiLineString multiObjects = (MultiLineString) spatialObject;
                            for (int i = 0; i < multiObjects.getNumGeometries(); i++) {
                                LineString oneObject = (LineString) multiObjects.getGeometryN(i);
                                oneObject.setUserData(multiObjects.getUserData());
                                result.add(oneObject);
                            }
                        }
                        else if (spatialObject instanceof LineString) {
                            result.add((LineString) spatialObject);
                        }
                        else {
                            throw new Exception("[ShapefileRDD][getLineStringRDD] the object type is not LineString or MultiLineString type. It is " + spatialObject.getGeometryType());
                        }
                        return result.iterator();
                    }
                })
        );
        lineStringRDD.fieldNames = geometryRDD.fieldNames;
        return lineStringRDD;
    }
}
