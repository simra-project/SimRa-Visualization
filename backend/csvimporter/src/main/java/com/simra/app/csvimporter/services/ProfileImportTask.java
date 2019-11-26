package main.java.com.simra.app.csvimporter.services;

import main.java.com.simra.app.csvimporter.handler.ProfileFileIOHandler;
import org.apache.log4j.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

public class ProfileImportTask implements Runnable {

    private static final Logger logger = Logger.getLogger(ProfileImportTask.class);
    private String filePath;


    public ProfileImportTask(String filePath) {
        this.filePath = filePath;
    }

    public String getFilePath() {
        return filePath;
    }

    public void run() {
        Path path = Paths.get(this.filePath);
        new ProfileFileIOHandler(path);
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }


    }
}
