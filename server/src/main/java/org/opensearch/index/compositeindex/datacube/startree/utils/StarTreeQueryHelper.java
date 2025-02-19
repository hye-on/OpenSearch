/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.compositeindex.datacube.startree.utils;

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.search.CollectionTerminatedException;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.FixedBitSet;
import org.opensearch.common.lucene.Lucene;
import org.opensearch.index.codec.composite.CompositeIndexFieldInfo;
import org.opensearch.index.codec.composite.CompositeIndexReader;
import org.opensearch.index.compositeindex.datacube.DateDimension;
import org.opensearch.index.compositeindex.datacube.Dimension;
import org.opensearch.index.compositeindex.datacube.Metric;
import org.opensearch.index.compositeindex.datacube.MetricStat;
import org.opensearch.index.compositeindex.datacube.startree.index.StarTreeValues;
import org.opensearch.index.compositeindex.datacube.startree.utils.date.DateTimeUnitAdapter;
import org.opensearch.index.compositeindex.datacube.startree.utils.date.DateTimeUnitRounding;
import org.opensearch.index.compositeindex.datacube.startree.utils.iterator.SortedNumericStarTreeValuesIterator;
import org.opensearch.index.mapper.CompositeDataCubeFieldType;
import org.opensearch.index.query.MatchAllQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.TermQueryBuilder;
import org.opensearch.search.aggregations.AggregatorFactory;
import org.opensearch.search.aggregations.LeafBucketCollector;
import org.opensearch.search.aggregations.StarTreeBucketCollector;
import org.opensearch.search.aggregations.bucket.histogram.DateHistogramAggregatorFactory;
import org.opensearch.search.aggregations.metrics.MetricAggregatorFactory;
import org.opensearch.search.aggregations.support.ValuesSource;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.internal.SearchContext;
import org.opensearch.search.startree.StarTreeFilter;
import org.opensearch.search.startree.StarTreeQueryContext;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Helper class for building star-tree query
 *
 * @opensearch.internal
 * @opensearch.experimental
 */
public class StarTreeQueryHelper {

    /**
     * Checks if the search context can be supported by star-tree
     */
    public static boolean isStarTreeSupported(SearchContext context) {
        return context.aggregations() != null && context.mapperService().isCompositeIndexPresent() && context.parsedPostFilter() == null;
    }

    /**
     * Gets StarTreeQueryContext from the search context and source builder.
     * Returns null if the query and aggregation cannot be supported.
     */
    public static StarTreeQueryContext getStarTreeQueryContext(SearchContext context, SearchSourceBuilder source) throws IOException {
        // Current implementation assumes only single star-tree is supported
        CompositeDataCubeFieldType compositeMappedFieldType = (CompositeDataCubeFieldType) context.mapperService()
            .getCompositeFieldTypes()
            .iterator()
            .next();
        CompositeIndexFieldInfo starTree = new CompositeIndexFieldInfo(
            compositeMappedFieldType.name(),
            compositeMappedFieldType.getCompositeIndexType()
        );

        for (AggregatorFactory aggregatorFactory : context.aggregations().factories().getFactories()) {
            // first check for aggregation is a metric aggregation
            if (validateStarTreeMetricSupport(compositeMappedFieldType, aggregatorFactory)) {
                continue;
            }

            // if not a metric aggregation, check for applicable date histogram shape
            if (validateDateHistogramSupport(compositeMappedFieldType, aggregatorFactory)) {
                continue;
            }
            return null;
        }

        // need to cache star tree values only for multiple aggregations
        boolean cacheStarTreeValues = context.aggregations().factories().getFactories().length > 1;
        int cacheSize = cacheStarTreeValues ? context.indexShard().segments(false).size() : -1;

        return StarTreeQueryHelper.tryCreateStarTreeQueryContext(starTree, compositeMappedFieldType, source.query(), cacheSize);
    }

    /**
     * Uses query builder and composite index info to form star-tree query context
     */
    private static StarTreeQueryContext tryCreateStarTreeQueryContext(
        CompositeIndexFieldInfo compositeIndexFieldInfo,
        CompositeDataCubeFieldType compositeFieldType,
        QueryBuilder queryBuilder,
        int cacheStarTreeValuesSize
    ) {
        Map<String, Long> queryMap;
        if (queryBuilder == null || queryBuilder instanceof MatchAllQueryBuilder) {
            queryMap = null;
        } else if (queryBuilder instanceof TermQueryBuilder termQueryBuilder) {
            // TODO: Add support for keyword fields
            Dimension matchedDimension = compositeFieldType.getDimensions()
                .stream()
                .filter(d -> (d.getField().equals(termQueryBuilder.fieldName()) && d.getDocValuesType() == DocValuesType.SORTED_NUMERIC))
                .findFirst()
                .orElse(null);
            if (matchedDimension == null) {
                return null;
            }
            queryMap = Map.of(termQueryBuilder.fieldName(), Long.parseLong(termQueryBuilder.value().toString()));
        } else {
            return null;
        }
        return new StarTreeQueryContext(compositeIndexFieldInfo, queryMap, cacheStarTreeValuesSize);
    }

    private static boolean validateStarTreeMetricSupport(
        CompositeDataCubeFieldType compositeIndexFieldInfo,
        AggregatorFactory aggregatorFactory
    ) {
        if (aggregatorFactory instanceof MetricAggregatorFactory metricAggregatorFactory
            && metricAggregatorFactory.getSubFactories().getFactories().length == 0) {
            String field;
            Map<String, List<MetricStat>> supportedMetrics = compositeIndexFieldInfo.getMetrics()
                .stream()
                .collect(Collectors.toMap(Metric::getField, Metric::getMetrics));

            MetricStat metricStat = metricAggregatorFactory.getMetricStat();
            field = metricAggregatorFactory.getField();

            return supportedMetrics.containsKey(field) && supportedMetrics.get(field).contains(metricStat);
        }
        return false;
    }

    private static boolean validateDateHistogramSupport(
        CompositeDataCubeFieldType compositeIndexFieldInfo,
        AggregatorFactory aggregatorFactory
    ) {
        if (!(aggregatorFactory instanceof DateHistogramAggregatorFactory dateHistogramAggregatorFactory)
            || aggregatorFactory.getSubFactories().getFactories().length < 1) {
            return false;
        }

        // Find the DateDimension in the dimensions list
        DateDimension starTreeDateDimension = null;
        for (Dimension dimension : compositeIndexFieldInfo.getDimensions()) {
            if (dimension instanceof DateDimension) {
                starTreeDateDimension = (DateDimension) dimension;
                break;
            }
        }

        // If no DateDimension is found, validation fails
        if (starTreeDateDimension == null) {
            return false;
        }

        // Ensure the rounding is not null
        if (dateHistogramAggregatorFactory.getRounding() == null) {
            return false;
        }

        // Find the closest valid interval in the DateTimeUnitRounding class associated with star tree
        DateTimeUnitRounding rounding = starTreeDateDimension.findClosestValidInterval(
            new DateTimeUnitAdapter(dateHistogramAggregatorFactory.getRounding())
        );
        if (rounding == null) {
            return false;
        }

        // Validate all sub-factories
        for (AggregatorFactory subFactory : aggregatorFactory.getSubFactories().getFactories()) {
            if (!validateStarTreeMetricSupport(compositeIndexFieldInfo, subFactory)) {
                return false;
            }
        }
        return true;
    }

    public static CompositeIndexFieldInfo getSupportedStarTree(SearchContext context) {
        StarTreeQueryContext starTreeQueryContext = context.getStarTreeQueryContext();
        return (starTreeQueryContext != null) ? starTreeQueryContext.getStarTree() : null;
    }

    public static StarTreeValues getStarTreeValues(LeafReaderContext context, CompositeIndexFieldInfo starTree) throws IOException {
        SegmentReader reader = Lucene.segmentReader(context.reader());
        if (!(reader.getDocValuesReader() instanceof CompositeIndexReader)) {
            return null;
        }
        CompositeIndexReader starTreeDocValuesReader = (CompositeIndexReader) reader.getDocValuesReader();
        return (StarTreeValues) starTreeDocValuesReader.getCompositeIndexValues(starTree);
    }

    /**
     * Get the star-tree leaf collector
     * This collector computes the aggregation prematurely and invokes an early termination collector
     */
    public static LeafBucketCollector getStarTreeLeafCollector(
        SearchContext context,
        ValuesSource.Numeric valuesSource,
        LeafReaderContext ctx,
        LeafBucketCollector sub,
        CompositeIndexFieldInfo starTree,
        String metric,
        Consumer<Long> valueConsumer,
        Runnable finalConsumer
    ) throws IOException {
        StarTreeValues starTreeValues = getStarTreeValues(ctx, starTree);
        assert starTreeValues != null;
        String fieldName = ((ValuesSource.Numeric.FieldData) valuesSource).getIndexFieldName();
        String metricName = StarTreeUtils.fullyQualifiedFieldNameForStarTreeMetricsDocValues(starTree.getField(), fieldName, metric);

        assert starTreeValues != null;
        SortedNumericStarTreeValuesIterator valuesIterator = (SortedNumericStarTreeValuesIterator) starTreeValues.getMetricValuesIterator(
            metricName
        );
        // Obtain a FixedBitSet of matched star tree document IDs
        FixedBitSet filteredValues = getStarTreeFilteredValues(context, ctx, starTreeValues);
        assert filteredValues != null;

        int numBits = filteredValues.length();  // Get the number of the filtered values (matching docs)
        if (numBits > 0) {
            // Iterate over the filtered values
            for (int bit = filteredValues.nextSetBit(0); bit != DocIdSetIterator.NO_MORE_DOCS; bit = (bit + 1 < numBits)
                ? filteredValues.nextSetBit(bit + 1)
                : DocIdSetIterator.NO_MORE_DOCS) {
                // Advance to the entryId in the valuesIterator
                if (valuesIterator.advanceExact(bit) == false) {
                    continue;  // Skip if no more entries
                }

                // Iterate over the values for the current entryId
                for (int i = 0, count = valuesIterator.entryValueCount(); i < count; i++) {
                    long value = valuesIterator.nextValue();
                    valueConsumer.accept(value); // Apply the consumer operation (e.g., max, sum)
                }
            }
        }

        // Call the final consumer after processing all entries
        finalConsumer.run();

        // Terminate after pre-computing aggregation
        throw new CollectionTerminatedException();
    }

    public static StarTreeBucketCollector getStarTreeBucketMetricCollector(
        CompositeIndexFieldInfo starTree,
        String metric,
        ValuesSource.Numeric valuesSource,
        StarTreeBucketCollector parentCollector,
        Consumer<Long> growArrays,
        BiConsumer<Long, Long> updateBucket
    ) throws IOException {
        assert parentCollector != null;
        return new StarTreeBucketCollector(parentCollector) {
            String metricName = StarTreeUtils.fullyQualifiedFieldNameForStarTreeMetricsDocValues(
                starTree.getField(),
                ((ValuesSource.Numeric.FieldData) valuesSource).getIndexFieldName(),
                metric
            );
            SortedNumericStarTreeValuesIterator metricValuesIterator = (SortedNumericStarTreeValuesIterator) starTreeValues
                .getMetricValuesIterator(metricName);

            @Override
            public void collectStarTreeEntry(int starTreeEntryBit, long bucket) throws IOException {
                growArrays.accept(bucket);
                // Advance the valuesIterator to the current bit
                if (!metricValuesIterator.advanceExact(starTreeEntryBit)) {
                    return; // Skip if no entries for this document
                }
                long metricValue = metricValuesIterator.nextValue();
                updateBucket.accept(bucket, metricValue);
            }
        };
    }

    /**
     * Get the filtered values for the star-tree query
     * Cache the results in case of multiple aggregations (if cache is initialized)
     * @return FixedBitSet of matched document IDs
     */
    public static FixedBitSet getStarTreeFilteredValues(SearchContext context, LeafReaderContext ctx, StarTreeValues starTreeValues)
        throws IOException {
        FixedBitSet result = context.getStarTreeQueryContext().getStarTreeValues(ctx);
        if (result == null) {
            result = StarTreeFilter.getStarTreeResult(starTreeValues, context.getStarTreeQueryContext().getQueryMap(), Set.of());
            context.getStarTreeQueryContext().setStarTreeValues(ctx, result);
        }
        return result;
    }
}
