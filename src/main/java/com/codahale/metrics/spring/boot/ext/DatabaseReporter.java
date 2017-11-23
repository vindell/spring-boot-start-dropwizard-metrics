/*
 * Copyright (c) 2010-2020, vindell (https://github.com/vindell).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.codahale.metrics.spring.boot.ext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Clock;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricAttribute;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import com.codahale.metrics.spring.boot.exception.MetricsException;
import com.codahale.metrics.spring.boot.utils.jdbc.JdbcUtils;
import com.codahale.metrics.spring.boot.utils.jdbc.MetricsSqlException;
import com.codahale.metrics.spring.boot.utils.jdbc.RollbackWithSavepointException;

/**
 * A reporter which creates a comma-separated values file of the measurements
 * for each metric.
 */
public class DatabaseReporter extends ScheduledReporter {

	/**
	 * Returns a new {@link Builder} for {@link DatabaseReporter}.
	 *
	 * @param registry
	 *            the registry to report
	 * @return a {@link Builder} instance for a {@link DatabaseReporter}
	 */
	public static Builder forRegistry(MetricRegistry registry) {
		return new Builder(registry);
	}

	/**
	 * A builder for {@link DatabaseReporter} instances. Defaults to using the
	 * default locale, converting rates to events/second, converting durations to
	 * milliseconds, and not filtering metrics.
	 */
	public static class Builder {
		
		private final MetricRegistry registry;
		private TimeUnit rateUnit;
		private TimeUnit durationUnit;
		private Clock clock;
		private MetricFilter filter;
		private ScheduledExecutorService executor;
		private boolean shutdownExecutorOnStop;
		/**
		 * Whether to roll back the transaction when an exception is thrown.
		 */
		private boolean rollbackOnException;
		/**
		 * Whether to close the connection when a statement is commit.
		 */
		private boolean closeOnCommit;

		private String caugeTable = "cauge_metrics";
		private String histogramTable = "histogram_metrics";
		private String meterTable = "meter_metrics";
		private String timerTable = "timer_metrics";

		private Builder(MetricRegistry registry) {
			this.registry = registry;
			this.rateUnit = TimeUnit.SECONDS;
			this.durationUnit = TimeUnit.MILLISECONDS;
			this.clock = Clock.defaultClock();
			this.filter = MetricFilter.ALL;
			this.executor = null;
			this.shutdownExecutorOnStop = true;
			this.rollbackOnException = true;
			this.closeOnCommit = true;
		}

		public Builder setShutdownExecutorOnStop(boolean shutdownExecutorOnStop) {
			this.shutdownExecutorOnStop = shutdownExecutorOnStop;
			return this;
		}

		public Builder setCloseOnCommit(boolean closeOnCommit) {
			this.closeOnCommit = closeOnCommit;
			return this;
		}

		/**
		 * Specifies whether or not, the executor (used for reporting) will be stopped
		 * with same time with reporter. Default value is true. Setting this parameter
		 * to false, has the sense in combining with providing external managed executor
		 * via {@link #scheduleOn(ScheduledExecutorService)}.
		 *
		 * @param shutdownExecutorOnStop
		 *            if true, then executor will be stopped in same time with this
		 *            reporter
		 * @return {@code this}
		 */
		public Builder shutdownExecutorOnStop(boolean shutdownExecutorOnStop) {
			this.shutdownExecutorOnStop = shutdownExecutorOnStop;
			return this;
		}

		/**
		 * Specifies the executor to use while scheduling reporting of metrics. Default
		 * value is null. Null value leads to executor will be auto created on start.
		 *
		 * @param executor
		 *            the executor to use while scheduling reporting of metrics.
		 * @return {@code this}
		 */
		public Builder scheduleOn(ScheduledExecutorService executor) {
			this.executor = executor;
			return this;
		}

		/**
		 * Convert rates to the given time unit.
		 *
		 * @param rateUnit
		 *            a unit of time
		 * @return {@code this}
		 */
		public Builder convertRatesTo(TimeUnit rateUnit) {
			this.rateUnit = rateUnit;
			return this;
		}

		/**
		 * Convert durations to the given time unit.
		 *
		 * @param durationUnit
		 *            a unit of time
		 * @return {@code this}
		 */
		public Builder convertDurationsTo(TimeUnit durationUnit) {
			this.durationUnit = durationUnit;
			return this;
		}

		/**
		 * Use the given {@link Clock} instance for the time.
		 *
		 * @param clock
		 *            a {@link Clock} instance
		 * @return {@code this}
		 */
		public Builder withClock(Clock clock) {
			this.clock = clock;
			return this;
		}

		/**
		 * Only report metrics which match the given filter.
		 *
		 * @param filter
		 *            a {@link MetricFilter}
		 * @return {@code this}
		 */
		public Builder filter(MetricFilter filter) {
			this.filter = filter;
			return this;
		}

		/**
		 * @param dataSource
		 * @return a {@link DatabaseReporter}
		 */
		public DatabaseReporter build(DataSource dataSource) {
			return new DatabaseReporter(registry, rateUnit, durationUnit, clock, filter, executor,
					shutdownExecutorOnStop, dataSource, rollbackOnException, closeOnCommit, caugeTable, histogramTable,
					meterTable, timerTable);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseReporter.class);

	private final Clock clock;
	private final DataSource dataSource;
	/**
	 * Whether to roll back the transaction when an exception is thrown.
	 */
	private final boolean rollbackOnException;
	/**
	 * Whether to close the connection when a statement is commit.
	 */
	private final boolean closeOnCommit;

	private final String caugeTable;
	private final String histogramTable;
	private final String meterTable;
	private final String timerTable;

	private DatabaseReporter(MetricRegistry registry, TimeUnit rateUnit,
			TimeUnit durationUnit, Clock clock, MetricFilter filter, ScheduledExecutorService executor,
			boolean shutdownExecutorOnStop, DataSource dataSource, boolean rollbackOnException, boolean closeOnCommit,
			String caugeTable, String histogramTable, String meterTable, String timerTable) {
		super(registry, "database-reporter", filter, rateUnit, durationUnit, executor, shutdownExecutorOnStop);
		this.clock = clock;
		this.dataSource = dataSource;
		this.rollbackOnException = rollbackOnException;
		this.closeOnCommit = closeOnCommit;
		this.caugeTable = caugeTable;
		this.histogramTable = histogramTable;
		this.meterTable = meterTable;
		this.timerTable = timerTable;
	}

	@Override
	public void report(SortedMap<String, Gauge> gauges, 
			SortedMap<String, Counter> counters,
			SortedMap<String, Histogram> histograms,
			SortedMap<String, Meter> meters, 
			SortedMap<String, Timer> timers) {
		final long timestamp = TimeUnit.MILLISECONDS.toSeconds(clock.getTime());

		Connection connection = null;
		boolean oldAutocommit = true;
		try {

			connection = JdbcUtils.openConnection(dataSource);
			oldAutocommit = connection.getAutoCommit();
			connection.setAutoCommit(false);

			reportGauges(connection, timestamp, gauges);

			reportCounters(connection, timestamp, counters);

			reportHistograms(connection, timestamp, histograms);

			reportMeters(connection, timestamp, meters);

			reportTimers(connection, timestamp, timers);

			connection.commit();

		} catch (SQLException e) {
			throw new MetricsSqlException("Unable to commit transaction", e);
		} catch (Exception e) {
			Savepoint savepoint = null;
			RuntimeException rethrow;
			if (e instanceof RollbackWithSavepointException) {
				savepoint = ((RollbackWithSavepointException) e).getSavepoint();
				rethrow = (RuntimeException) e.getCause();
			} else if (e instanceof RuntimeException) {
				rethrow = (RuntimeException) e;
			} else {
				rethrow = new MetricsException(e);
			}

			if (rollbackOnException) {
				try {
					LOGGER.debug("Rolling back transaction...");
					if (savepoint == null) {
						connection.rollback();
					} else {
						connection.rollback(savepoint);
					}
					LOGGER.debug("Transaction rolled back");
				} catch (SQLException se) {
					LOGGER.error("Unable to rollback transaction", se);
				}
			} else {
				try {
					connection.commit();
				} catch (SQLException se) {
					LOGGER.error("Unable to commit transaction", se);
				}
			}
			throw rethrow;
		} finally {
			try {
				connection.setAutoCommit(oldAutocommit);
			} catch (SQLException e) {
				LOGGER.error("Unable to restore autocommit to original value for connection", e);
			}
			if (this.closeOnCommit) {
				JdbcUtils.closeConnection(connection);
			}
		}

	}

	@SuppressWarnings("rawtypes")
	private void reportGauges(Connection connection, long timestamp, SortedMap<String, Gauge> gauges)
			throws SQLException {
		
		StringBuilder sqlBuilder = new StringBuilder("insert into ").append(caugeTable)
				.append("(timestamp, name, value) values (?,?,?)");
		// 对SQL进行处理，生成预编译Statement
		PreparedStatement pstmt = connection.prepareStatement(sqlBuilder.toString());

		for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
			final String name = entry.getKey();
			final Gauge gauge = entry.getValue();

			// 设置预定义参数
			pstmt.setObject(0, timestamp);
			pstmt.setObject(1, name);
			pstmt.setObject(2, gauge.getValue());

			// 再添加一次预定义参数
			pstmt.addBatch();
		}

		pstmt.executeBatch();
	}
	 
	
	private void reportCounters(Connection connection, long timestamp, SortedMap<String, Counter> counters)
			throws SQLException {
		
		StringBuilder sqlBuilder = new StringBuilder("insert into ").append(caugeTable)
				.append("(timestamp, name, count) values (?,?,?)");
		// 对SQL进行处理，生成预编译Statement
		PreparedStatement pstmt = connection.prepareStatement(sqlBuilder.toString());
		
		for (Map.Entry<String, Counter> entry : counters.entrySet()) {
			final String name = entry.getKey();
			final Counter counter = entry.getValue();
			
			// 设置预定义参数
			pstmt.setObject(0, timestamp);
			pstmt.setObject(1, name);
			pstmt.setObject(2, counter.getCount());

			// 再添加一次预定义参数
			pstmt.addBatch();

		}
		
		pstmt.executeBatch();

	}


	private void reportHistograms(Connection connection, long timestamp, SortedMap<String, Histogram> histograms)
			throws SQLException {
		
		StringBuilder sqlBuilder = new StringBuilder("insert into ").append(histogramTable)
				.append("(timestamp, name,")
				.append(MetricAttribute.COUNT.getCode()).append(",")
				.append(MetricAttribute.MAX.getCode()).append(",")
				.append(MetricAttribute.MEAN.getCode()).append(",")
				.append(MetricAttribute.MIN.getCode()).append(",")
				.append(MetricAttribute.STDDEV.getCode()).append(",")
				.append(MetricAttribute.P50.getCode()).append(",")
				.append(MetricAttribute.P75.getCode()).append(",")
				.append(MetricAttribute.P95.getCode()).append(",")
				.append(MetricAttribute.P98.getCode()).append(",")
				.append(MetricAttribute.P99.getCode()).append(",")
				.append(MetricAttribute.P999.getCode()).append(",")
				.append(") values (?,?,?,?,?,?,?,?,?,?,?,?,?)");
		// 对SQL进行处理，生成预编译Statement
		PreparedStatement pstmt = connection.prepareStatement(sqlBuilder.toString());
		
		for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
			final String name = entry.getKey();
			final Histogram histogram = entry.getValue();

			final Snapshot snapshot = histogram.getSnapshot();
			
			// 设置预定义参数
			pstmt.setObject(0, timestamp);
			pstmt.setObject(1, name);
			pstmt.setObject(2, histogram.getCount());
			pstmt.setObject(3, snapshot.getMax());
			pstmt.setObject(4, snapshot.getMean());
			pstmt.setObject(5, snapshot.getMin());
			pstmt.setObject(6, snapshot.getStdDev());
			pstmt.setObject(7, snapshot.getMedian());
			pstmt.setObject(8, snapshot.get75thPercentile());
			pstmt.setObject(9, snapshot.get95thPercentile());
			pstmt.setObject(10, snapshot.get98thPercentile());
			pstmt.setObject(11, snapshot.get99thPercentile());
			pstmt.setObject(12, snapshot.get999thPercentile());

			// 再添加一次预定义参数
			pstmt.addBatch();

		}
		
		pstmt.executeBatch();
		 
	}
	

	private void reportMeters(Connection connection, long timestamp, SortedMap<String, Meter> meters)
			throws SQLException {
		
		StringBuilder sqlBuilder = new StringBuilder("insert into ").append(meterTable)
				.append("(timestamp, name,")
				.append(MetricAttribute.COUNT.getCode()).append(",")
				.append(MetricAttribute.MEAN_RATE.getCode()).append(",")
				.append(MetricAttribute.M1_RATE.getCode()).append(",")
				.append(MetricAttribute.M5_RATE.getCode()).append(",")
				.append(MetricAttribute.M15_RATE.getCode()).append(",")
				.append("rate_unit ) values (?,?,?,?,?,?,?,?)");
		// 对SQL进行处理，生成预编译Statement
		PreparedStatement pstmt = connection.prepareStatement(sqlBuilder.toString());
		
		for (Map.Entry<String, Meter> entry : meters.entrySet()) {
			final String name = entry.getKey();
			final Meter meter = entry.getValue();
			
			// 设置预定义参数
			pstmt.setObject(0, timestamp);
			pstmt.setObject(1, name);
			pstmt.setObject(2, meter.getCount());
			pstmt.setObject(3, convertRate(meter.getMeanRate()));
			pstmt.setObject(4, convertRate(meter.getOneMinuteRate()));
			pstmt.setObject(5, convertRate(meter.getFiveMinuteRate()));
			pstmt.setObject(6, convertRate(meter.getFifteenMinuteRate()));
			pstmt.setObject(7, getRateUnit());

			// 再添加一次预定义参数
			pstmt.addBatch();

		}
		
		pstmt.executeBatch();

	}
	
	private void reportTimers(Connection connection, long timestamp, SortedMap<String, Timer> timers) throws SQLException {
		
		
		StringBuilder sqlBuilder = new StringBuilder("insert into ").append(timerTable)
				.append("(timestamp, name,")
				.append(MetricAttribute.COUNT.getCode()).append(",")
				.append(MetricAttribute.MAX.getCode()).append(",")
				.append(MetricAttribute.MEAN.getCode()).append(",")
				.append(MetricAttribute.MIN.getCode()).append(",")
				.append(MetricAttribute.STDDEV.getCode()).append(",")
				.append(MetricAttribute.P50.getCode()).append(",")
				.append(MetricAttribute.P75.getCode()).append(",")
				.append(MetricAttribute.P95.getCode()).append(",")
				.append(MetricAttribute.P98.getCode()).append(",")
				.append(MetricAttribute.P99.getCode()).append(",")
				.append(MetricAttribute.P999.getCode()).append(",")
				.append(MetricAttribute.MEAN_RATE.getCode()).append(",")
				.append(MetricAttribute.M1_RATE.getCode()).append(",")
				.append(MetricAttribute.M5_RATE.getCode()).append(",")
				.append(MetricAttribute.M15_RATE.getCode()).append(",")
				.append("rate_unit,duration_unit) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
		// 对SQL进行处理，生成预编译Statement
		PreparedStatement pstmt = connection.prepareStatement(sqlBuilder.toString());
		
		for (Map.Entry<String, Timer> entry : timers.entrySet()) {

			final String name = entry.getKey();
			final Timer timer = entry.getValue();
			final Snapshot snapshot = timer.getSnapshot();
			
			// 设置预定义参数
			pstmt.setObject(0, timestamp);
			pstmt.setObject(1, name);
			pstmt.setObject(2, timer.getCount());
			pstmt.setObject(3, convertDuration(snapshot.getMax()));
			pstmt.setObject(4, convertDuration(snapshot.getMean()));
			pstmt.setObject(5, convertDuration(snapshot.getMin()));
			pstmt.setObject(6, convertDuration(snapshot.getStdDev()));
			pstmt.setObject(7, convertDuration(snapshot.getMedian()));
			pstmt.setObject(8, convertDuration(snapshot.get75thPercentile()));
			pstmt.setObject(9, convertDuration(snapshot.get95thPercentile()));
			pstmt.setObject(10, convertDuration(snapshot.get98thPercentile()));
			pstmt.setObject(11, convertDuration(snapshot.get99thPercentile()));
			pstmt.setObject(12, convertDuration(snapshot.get999thPercentile()));
			
			pstmt.setObject(13, convertRate(timer.getMeanRate()));
			pstmt.setObject(14, convertRate(timer.getOneMinuteRate()));
			pstmt.setObject(15, convertRate(timer.getFiveMinuteRate()));
			pstmt.setObject(16, convertRate(timer.getFifteenMinuteRate()));
			pstmt.setObject(17, getRateUnit());
			pstmt.setObject(18, getDurationUnit());

			// 再添加一次预定义参数
			pstmt.addBatch();

		}
		
		pstmt.executeBatch();

	}
 

}
