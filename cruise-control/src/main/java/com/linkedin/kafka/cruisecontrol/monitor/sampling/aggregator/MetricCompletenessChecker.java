/*
 * Copyright 2017 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License").  See License in the project root for license information.
 */

package com.linkedin.kafka.cruisecontrol.monitor.sampling.aggregator;

import com.linkedin.kafka.cruisecontrol.monitor.ModelGeneration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.TopicPartition;


/**
 * A class that helps compute the completeness of the metrics in the {@link MetricSampleAggregator}
 */
public class MetricCompletenessChecker {
  // The following two data structures help us to quickly identify how many valid partitions are there in each window.
  private final ConcurrentSkipListMap<Long, Map<String, Integer>> _validPartitionsPerTopicByWindows;
  private final SortedMap<Long, Integer> _validPartitionsByWindows;
  private final int _maxNumSnapshots;
  private volatile ModelGeneration _modelGeneration;
  private volatile long _activeSnapshotWindow;

  public MetricCompletenessChecker(int maxNumSnapshots) {
    _validPartitionsPerTopicByWindows = new ConcurrentSkipListMap<>();
    _validPartitionsByWindows = new TreeMap<>((o1, o2) -> Long.compare(o2, o1));
    _modelGeneration = null;
    _maxNumSnapshots = maxNumSnapshots;
  }

  /**
   * Get the number of valid windows that meets the the minimum monitored partitions percentage requirement.
   *
   * @param minMonitoredPartitionsPercentage the minimum monitored partitions percentage.
   * @param totalNumPartitions the total number of partitions.
   * @return the number of the most recent valid windows.
   */
  synchronized public int numValidWindows(ModelGeneration modelGeneration,
                                          Cluster cluster,
                                          double minMonitoredPartitionsPercentage,
                                          int totalNumPartitions) {
    computeMetricCompleteness(cluster, modelGeneration);
    int i = 0;
    double minMonitoredNumPartitions = totalNumPartitions * minMonitoredPartitionsPercentage;
    Iterator<Integer> iter = _validPartitionsByWindows.values().iterator();
    while (iter.hasNext() && i < _maxNumSnapshots) {
      long monitoredPartitions = iter.next();
      if (monitoredPartitions < minMonitoredNumPartitions) {
        break;
      }
      if (monitoredPartitions != _activeSnapshotWindow) {
        i++;
      }
    }
    return i;
  }

  synchronized public SortedMap<Long, Double> monitoredPercentages(ModelGeneration modelGeneration,
                                                                   Cluster cluster,
                                                                   int totalNumPartitions) {
    computeMetricCompleteness(cluster, modelGeneration);
    TreeMap<Long, Double> percentages = new TreeMap<>();
    for (Map.Entry<Long, Integer> entry : _validPartitionsByWindows.entrySet()) {
      percentages.put(entry.getKey(), (double) entry.getValue() / totalNumPartitions);
    }
    return percentages;
  }

  /**
   * Get number of snapshot windows in a period.
   */
  synchronized public int numWindows(long from, long to) {
    int i = 0;
    for (long window : _validPartitionsByWindows.keySet()) {
      // Exclude the active window.
      if (window >= from && window <= to && window != _validPartitionsByWindows.firstKey()) {
        i++;
      }
    }
    return i;
  }

  void updatePartitionCompleteness(MetricSampleAggregator aggregator, long window, TopicPartition tp) {
    _validPartitionsPerTopicByWindows.computeIfAbsent(window, w -> new ConcurrentHashMap<>())
                                     .compute(tp.topic(), (t, v) -> {
                                       int increment = aggregator.isValidPartition(window, tp) ? 1 : 0;
                                       return v == null ? increment : v + increment;
                                     });
    _activeSnapshotWindow = aggregator.activeSnapshotWindow();
  }

  synchronized void refreshAllPartitionCompleteness(MetricSampleAggregator aggregator,
                                                    Set<Long> windows,
                                                    Set<TopicPartition> partitions) {
    _validPartitionsPerTopicByWindows.clear();
    for (long window : windows) {
      for (TopicPartition tp : partitions) {
        updatePartitionCompleteness(aggregator, window, tp);
      }
    }
    // We need to reset the model generation here. This is because previously we did not populate the partition completeness
    // map and user may have queried and set the model generation to be up to date.
    _modelGeneration = null;
  }

  void removeWindow(long snapshotWindow) {
    _validPartitionsPerTopicByWindows.remove(snapshotWindow);
  }

  private void computeMetricCompleteness(Cluster cluster, ModelGeneration modelGeneration) {
    if (_modelGeneration == null || !_modelGeneration.equals(modelGeneration)) {
      _validPartitionsByWindows.clear();
      for (Map.Entry<Long, Map<String, Integer>> entry : _validPartitionsPerTopicByWindows.entrySet()) {
        long window = entry.getKey();
        for (String topic : entry.getValue().keySet()) {
          updateWindowCompleteness(cluster, window, topic);
        }
      }
      _modelGeneration = modelGeneration;
    }
  }

  private void updateWindowCompleteness(Cluster cluster, long window, String topic) {
    int numValidPartitions = _validPartitionsPerTopicByWindows.get(window).get(topic);
    if (cluster.partitionsForTopic(topic).size() == numValidPartitions) {
      _validPartitionsByWindows.compute(window, (w, v) -> v == null ? numValidPartitions : v + numValidPartitions);
    }
  }
}
