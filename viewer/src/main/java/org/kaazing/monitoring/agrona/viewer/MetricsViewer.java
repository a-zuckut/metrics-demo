package org.kaazing.monitoring.agrona.viewer;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.kaazing.monitoring.agrona.viewer.task.MetricsTask;
import org.kaazing.monitoring.agrona.viewer.task.MetricsTaskImpl;
import org.kaazing.monitoring.reader.api.MMFReader;
import org.kaazing.monitoring.reader.exception.MetricsReaderException;
import org.kaazing.monitoring.reader.file.location.MonitoringFolderAgrona;
import org.kaazing.monitoring.reader.impl.AgronaMonitoringDataProcessor;
import org.kaazing.monitoring.reader.impl.MonitoringDataProcessor;
import org.kaazing.monitoring.reader.impl.file.location.MonitoringFolderAgronaImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.co.real_logic.agrona.concurrent.SigInt;

/**
 * MetricsViewer displaying data from all Agrona monitoring files
 * A newly MetricsViewer Agrona file added dynamically results in the output of this application being updated
 * Observation: Agrona files can not be deleted dynamically at this moment while in use due to locks from reader/writer processes,
 * i.e. first the application would need to be shutdown in order to be able to delete the file 
 *
 */
public class MetricsViewer {
    public static final int UPDATE_INTERVAL = 2000;
    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsViewer.class);
    private static final int SCHEDULED_TASKS_SIZE = 10;

    private static MonitoringFolderAgrona agronaFolder = new MonitoringFolderAgronaImpl();
    /**
     * List of tasks to be scheduled
     */
    private static List<MetricsTask> tasks = new ArrayList<MetricsTask>();
    /**
     * List of already existing files prior to rereading existing Agrona monitoring files
     */
    private static List<String> alreadyExistingFiles = new ArrayList<String>();

    /**
     * Main method
     * @param args
     * @throws InterruptedException
     */
    public static void main(String[] args) throws InterruptedException {
        // Operations related to viewer init
        initializeViewer();

        // Allocate the maximum number of threads for this thread executor
        ScheduledExecutorService taskExecutor = Executors.newScheduledThreadPool(SCHEDULED_TASKS_SIZE);
        SigInt.register(() -> taskExecutor.shutdown());

        // First scheduled task - responsible with reading Agrona file names
        scheduleGetMonitoringFilesExecutor(taskExecutor);

        taskExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    /**
     * Method initializing viewer
     */
    private static void initializeViewer() {
        Date date = new Date();
        LOGGER.debug(date + " - Gateway monitor\n");
        LOGGER.debug("=========================");
        LOGGER.debug("Counter identifier: Counter value - Counter name");
    }

    /**
     * Method scheduling a get monitoring files executor
     * @param taskExecutor 
     */
    private static void scheduleGetMonitoringFilesExecutor(ScheduledExecutorService taskExecutor) {
        taskExecutor.scheduleAtFixedRate(() -> {
           // Get new monitoring files list
            List<String> files = agronaFolder.getMonitoringFiles();

            updatescheduledTasksBasedOnFiles(files, taskExecutor);
        }, 0, UPDATE_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * Update the metrics collectors based on files
     * @param files
     * @param taskExecutor 
     */
    private static void updatescheduledTasksBasedOnFiles(List<String> files, ScheduledExecutorService taskExecutor) {
        if (alreadyExistingFiles.size() == 0) {
            //Add tasks for all found monitoring files
            for (int i = 0; i < files.size(); i++) {
                addMetricsCollector(files.get(i), taskExecutor);
            }
        }
        else {
            //Add tasks for all new monitoring files
            for (int i = 0; i < files.size(); i++) {
                if (!alreadyExistingFiles.contains(files.get(i))) {
                    addMetricsCollector(files.get(i), taskExecutor);
                }
            }
            //Remove tasks for removed monitoring files
            for (int i = 0; i < alreadyExistingFiles.size(); i++) {
                if (!files.contains(alreadyExistingFiles.get(i))) {
                    LOGGER.debug("Removing metrics collector for " + alreadyExistingFiles.get(i));
                    int indexToRemove = getTaskBasedOnFileName(alreadyExistingFiles.get(i));
                    tasks.get(indexToRemove).cleanup();
                    tasks.remove(indexToRemove);
                }
            }
        }
        alreadyExistingFiles = new ArrayList<String>(files);
    }

    /**
     * Method adding metrics collector
     * @param fileName
     * @param taskExecutor
     */
    private static void addMetricsCollector(String fileName, ScheduledExecutorService taskExecutor) {
        LOGGER.debug("Adding metrics collector for " + fileName);
        MonitoringDataProcessor monitoringDataProcessor = new AgronaMonitoringDataProcessor(fileName);
        try {
            monitoringDataProcessor.initialize();
        } catch (MetricsReaderException e) {
            LOGGER.error("There was a problem initializing the metrics reader. Exiting application.");
            System.exit(1);
        }
        MMFReader reader = monitoringDataProcessor.getMMFReader();
        if (reader == null) {
            LOGGER.error("There was a problem initializing the metrics reader. Exiting application.");
            System.exit(1);
        }
        tasks.add(new MetricsTaskImpl(fileName, taskExecutor, reader));
    }

    /**
     * Helper method which returns task index based on file name
     * @param file
     * @return
     */
    private static int getTaskBasedOnFileName(String file) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getFileName().equals(file)) {
                return i;
            }
        }
        return -1;
    }
}
