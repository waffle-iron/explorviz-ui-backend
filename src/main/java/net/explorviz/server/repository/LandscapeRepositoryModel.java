package net.explorviz.server.repository;

import java.io.FileNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.esotericsoftware.kryo.Kryo;

import net.explorviz.server.main.Configuration;
import net.explorviz.model.Application;
import net.explorviz.model.Clazz;
import net.explorviz.model.Communication;
import net.explorviz.model.CommunicationClazz;
import net.explorviz.model.Component;
import net.explorviz.model.Landscape;
import net.explorviz.model.Node;
import net.explorviz.model.NodeGroup;
import net.explorviz.model.System;
import explorviz.live_trace_processing.reader.IPeriodicTimeSignalReceiver;
import explorviz.live_trace_processing.reader.TimeSignalReader;
import explorviz.live_trace_processing.record.IRecord;

public class LandscapeRepositoryModel implements IPeriodicTimeSignalReceiver {
	private static final boolean LOAD_LAST_LANDSCAPE_ON_LOAD = false;

	private Landscape lastPeriodLandscape;
	private final Landscape internalLandscape;
	private final Kryo kryo;
	private final InsertionRepositoryPart insertionRepositoryPart;
	private final RemoteCallRepositoryPart remoteCallRepositoryPart;
	
	private static LandscapeRepositoryModel INSTANCE = null;
	
	

	public static synchronized LandscapeRepositoryModel getInstance() {
		if (LandscapeRepositoryModel.INSTANCE == null) {
			LandscapeRepositoryModel.INSTANCE = new LandscapeRepositoryModel();
		}
		return LandscapeRepositoryModel.INSTANCE;
	}

	public final Landscape getLastPeriodLandscape() {
		synchronized (lastPeriodLandscape) {
			return lastPeriodLandscape;
		}
	}

	public final Landscape getLandscape(final long timestamp) throws FileNotFoundException {
		return LandscapePreparer.prepareLandscape(RepositoryStorage.readFromFile(timestamp));
	}

	public final Map<Long, Long> getAvailableLandscapes() {
		return RepositoryStorage.getAvailableModelsForTimeshift();
	}

	static {
		Configuration.databaseNames.add("hsqldb");
		Configuration.databaseNames.add("postgres");
		Configuration.databaseNames.add("db2");
		Configuration.databaseNames.add("mysql");
		Configuration.databaseNames.add("neo4j");
		Configuration.databaseNames.add("database");
		Configuration.databaseNames.add("hypersql");
	}

	private LandscapeRepositoryModel() {
		kryo = initKryo();

		if (LOAD_LAST_LANDSCAPE_ON_LOAD) {
			Landscape readLandscape = null;
			try {
				readLandscape = RepositoryStorage.readFromFile(java.lang.System.currentTimeMillis());
			} catch (final FileNotFoundException e) {
				readLandscape = new Landscape();
			}

			internalLandscape = readLandscape;
		} else {
			internalLandscape = new Landscape();
		}

		insertionRepositoryPart = new InsertionRepositoryPart();
		remoteCallRepositoryPart = new RemoteCallRepositoryPart();

		internalLandscape.updateLandscapeAccess(java.lang.System.nanoTime());

		lastPeriodLandscape = LandscapePreparer.prepareLandscape(kryo.copy(internalLandscape));

		new TimeSignalReader(TimeUnit.SECONDS.toMillis(Configuration.outputIntervalSeconds), this).start();
	}

	public Kryo initKryo() {
		return RepositoryStorage.createKryoInstance();
	}

	public void reset() {
		synchronized (internalLandscape) {
			internalLandscape.getApplicationCommunication().clear();
			internalLandscape.getSystems().clear();
			internalLandscape.getEvents().clear();
			internalLandscape.getErrors().clear();
			internalLandscape.setActivities(0L);
			internalLandscape.updateLandscapeAccess(java.lang.System.nanoTime());
		}
	}

	@Override
	public void periodicTimeSignal(final long timestamp) {
		synchronized (internalLandscape) {
			synchronized (lastPeriodLandscape) {
				RepositoryStorage.writeToFile(internalLandscape, java.lang.System.currentTimeMillis());
				lastPeriodLandscape = LandscapePreparer.prepareLandscape(kryo.copy(internalLandscape));

				remoteCallRepositoryPart.checkForTimedoutRemoteCalls();

				resetCommunication();
			}
		}

		RepositoryStorage.cleanUpTooOldFiles(java.lang.System.currentTimeMillis());
	}

	private void resetCommunication() {
		internalLandscape.getErrors().clear();
		internalLandscape.setActivities(0L);

		for (final System system : internalLandscape.getSystems()) {
			for (final NodeGroup nodeGroup : system.getNodeGroups()) {
				for (final Node node : nodeGroup.getNodes()) {
					for (final Application app : node.getApplications()) {
						app.getDatabaseQueries().clear();

						for (final CommunicationClazz commu : app.getCommunications()) {
							commu.reset();
						}

						resetClazzInstances(app.getComponents());
					}
				}
			}
		}

		for (final Communication commu : internalLandscape.getApplicationCommunication()) {
			commu.setRequests(0);
			commu.setAverageResponseTimeInNanoSec(0);
		}

		internalLandscape.updateLandscapeAccess(java.lang.System.nanoTime());
	}

	private void resetClazzInstances(final List<Component> components) {
		for (final Component compo : components) {
			for (final Clazz clazz : compo.getClazzes()) {
				clazz.getObjectIds().clear();
				clazz.setInstanceCount(0);
			}

			resetClazzInstances(compo.getChildren());
		}
	}

	public void insertIntoModel(final IRecord inputIRecord) {
		insertionRepositoryPart.insertIntoModel(inputIRecord, internalLandscape, remoteCallRepositoryPart);
	}
}
