package com.simra.app.csvimporter.service;

import com.opencsv.bean.ColumnPositionMappingStrategy;
import com.opencsv.bean.CsvToBeanBuilder;
import com.simra.app.csvimporter.mapmatching.MapMatchingService;
import com.simra.app.csvimporter.model.CSVFile;
import com.simra.app.csvimporter.model.ProfileEntity;
import com.simra.app.csvimporter.repository.CSVFileRepository;
import com.simra.app.csvimporter.repository.IncidentRepository;
import com.simra.app.csvimporter.repository.ProfileRepository;
import com.simra.app.csvimporter.repository.RideRepository;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.StreamSupport;

@Service
public class SimraFileAlterationListenerAdaptor extends FileAlterationListenerAdaptor {
    private static final Logger LOG = LoggerFactory.getLogger(SimraFileAlterationListenerAdaptor.class);

    @Autowired
    private CSVFileRepository csvFileRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private MapMatchingService mapMatchingService;

    @Autowired
    private LegPartitioningService legPartitioningService;

    private ExecutorService rideIncidentExecutor;


    @Value("${min_accuracy}")
    private float minAccuracy;

    @Value("${rdp_epsilion}")
    private double rdpEpsilion;

    @Value("${simra.region.default}")
    private String region;

    @Value("${min_ride_distance}")
    private Integer minRideDistance;

    @Value("${min_ride_duration}")
    private Integer minRideDuration;

    @Value("${max_ride_average_speed}")
    private Integer maxRideAverageSpeed;

    @Value("${min_distance_to_cover_by_user_in_5_min}")
    private Integer minDistanceToCoverByUserIn5Min;

    @PostConstruct
    public void init() {
        rideIncidentExecutor = Executors.newFixedThreadPool(20);
    }

    @Override
    public void onDirectoryCreate(File file) {
        LOG.info("Folder created: {} ", file.getName());
    }

    @Override
    public void onDirectoryDelete(File file) {
        LOG.info("Folder deleted: {}", file.getName());
    }

    @Override
    public void onFileCreate(File file) {
        genericFileParser(file);
        LOG.info("File created: {}", file.getName());
    }

    @Override
    public void onFileDelete(File file) {
        LOG.info("File deleted: {}", file.getName());
    }


    private void genericFileParser(File f) {
        if (f.getName().contains("VM")) {
            // Check of Already Parsed.
            if (csvFileRepository.findByFileId(f.getName()) == null) {
                // Differentiates Profile & Ride
                String type = "";
                try {
                    String csvString = FileUtils.readFileToString(f, StandardCharsets.UTF_8);
                    getRegionFromPath(f);
                    // Pass to correct Parser.
                    if (csvString.contains("birth")) {
                        // Handle as Profile
                        type = "P";
                        profileParser(f);
                    } else {
                        // Handle as incident and ride
                        type = "R";
                        incidentRideParser(f, csvString);

                    }
                } catch (IOException e) {
                    LOG.error("File read failed: {}", e.getMessage());
                }
                // Save status into Database.
                csvFileRepository.save(new CSVFile(f.getName(), type));
            } else {
                LOG.info("File already checked: {}", f.getName());
            }
        }
    }


    private void profileParser(File f) {
        try (BufferedReader reader =
                     new BufferedReader(new FileReader(f))) {
            String line = reader.readLine();
            String[] arrOfStr = line.split("#");

            StringBuilder profileCSVwithHeader = new StringBuilder();
            while (line != null) {
                line = reader.readLine();
                if (line != null) {
                    profileCSVwithHeader.append(line);
                    profileCSVwithHeader.append("\r\n");
                }
            }

            ColumnPositionMappingStrategy<ProfileEntity> strategy = new ColumnPositionMappingStrategy<>();
            strategy.setType(ProfileEntity.class);
            String[] memberFieldsToBindTo = {"birth", "gender", "region", "experience",
                    "numberOfRides", "duration", "numberOfIncidents", "waitedTime", "distance", "co2", "0", "1",
                    "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20",
                    "21", "22", "23", "behaviour", "numberOfScary"};
            strategy.setColumnMapping(memberFieldsToBindTo);

            List<ProfileEntity> profileBeans = new CsvToBeanBuilder<ProfileEntity>(new StringReader(profileCSVwithHeader.toString()))
                    .withMappingStrategy(strategy)
                    .withSkipLines(1)
                    .withIgnoreLeadingWhiteSpace(true)
                    .build().parse();
            if (!profileBeans.isEmpty()) {
                ProfileEntity profile = profileBeans.get(0);
                profile.setId(f.getName());
                profile.setDirectoryRegion(this.region);
                profile.setFileId(f.getName());
                profile.setAppVersion(arrOfStr[0]);
                profile.setFileVersion(Integer.parseInt(arrOfStr[1]));
                /*
                 * Any pre profile filter starts here.
                 */
                profileRepository.save(profile);
            }


        } catch (IOException e) {
            LOG.error(String.valueOf(e));
        }
    }


    private void incidentRideParser(File f, String csvString) {
        /*
         * Start thread to parse file and save incidents
         * INFO: QUEUE
         */

        HashMap<String, Object> paramsIncidentParser = new HashMap<>();
        paramsIncidentParser.put("incidentRepository", incidentRepository);
        paramsIncidentParser.put("executor", this.rideIncidentExecutor);


        RideParserThreaded rideParserThreaded = new RideParserThreaded(f.getName(), rideRepository, minAccuracy, rdpEpsilion, mapMatchingService, legPartitioningService, csvString, this.region, this.minRideDistance, this.minRideDuration, this.maxRideAverageSpeed, this.minDistanceToCoverByUserIn5Min, paramsIncidentParser);
        this.rideIncidentExecutor.execute(rideParserThreaded);

    }

    private void getRegionFromPath(File f) {
        String[] pathSection = this.splitPath(f.getPath());
        // find index of folder Rides/Profile/Demo and select one before it as region.
        List<String> list = Arrays.asList(pathSection);
        int rideIndex = list.indexOf("Rides");
        int profileIndex = list.indexOf("Profiles");

        // just for test
        int demoIndex = list.indexOf("Demo");

        if (rideIndex > -1) {
            this.region = list.get(rideIndex - 1);
        } else if (profileIndex > -1) {
            this.region = list.get(profileIndex - 1);
        } else if (demoIndex > -1) {
            this.region = list.get(demoIndex - 1);
        }
    }


    private String[] splitPath(String pathString) {
        Path path = Paths.get(pathString);
        return StreamSupport.stream(path.spliterator(), false).map(Path::toString)
                .toArray(String[]::new);
    }


}
