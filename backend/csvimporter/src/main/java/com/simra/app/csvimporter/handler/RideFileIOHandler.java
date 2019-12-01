package main.java.com.simra.app.csvimporter.handler;

import com.opencsv.bean.ColumnPositionMappingStrategy;
import com.opencsv.bean.CsvToBeanBuilder;
import main.java.com.simra.app.csvimporter.filter.RideFilter;
import main.java.com.simra.app.csvimporter.mapmatching.MapMatchingService;
import main.java.com.simra.app.csvimporter.model.IncidentCSV;
import main.java.com.simra.app.csvimporter.model.Ride;
import main.java.com.simra.app.csvimporter.model.RideCSV;
import main.java.com.simra.app.csvimporter.services.ConfigService;
import main.java.com.simra.app.csvimporter.services.DBService;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

public class RideFileIOHandler {
    private static final Logger logger = Logger.getLogger(RideFileIOHandler.class);

    private static final String FILEVERSIONSPLITTER = "#";
    private Path filePath;
    private String region;
    private RideFilter rideFilter;
    private MapMatchingService mapMatchingService = new MapMatchingService();
    private static DBService dbService; // TODO: why is this static?


    public RideFileIOHandler(Path filePath, String region, Float minAccuracy, Double rdpEpsilon) {
        this.rideFilter = new RideFilter(minAccuracy, rdpEpsilon);
        this.filePath = filePath;
        this.region = region;
        dbService = new DBService();
    }

    public void parseFile() {
        try (BufferedReader reader = Files.newBufferedReader(this.filePath, StandardCharsets.UTF_8)) {
            StringBuilder incidentContent = new StringBuilder();
            StringBuilder rideContent = new StringBuilder();

            boolean switchStream = false;
            String line = reader.readLine();

            while (line != null) {
                if (line.contains("==")) {
                    switchStream = true;
                }
                if (switchStream) {
                    rideContent.append(line);
                    rideContent.append("\r\n ");
                } else {
                    incidentContent.append(line);
                    incidentContent.append("\r\n");
                }
                line = reader.readLine();
            }
            /*CSV to Java Object*/
            Ride ride = new Ride();
            ride.setRegion(region);

            if (incidentContent.length() > 0) {
                this.incidentParse(ride, incidentContent, this.filePath);
            }
            if (rideContent.length() > 0) {
                this.rideParse(ride, rideContent, this.filePath);
            }
            /*CSV to Java Object DONE*/


            if (ride.getRideBeans().isEmpty()) {
                if (Boolean.getBoolean(ConfigService.config.getProperty("debug"))) {
                    ride.getRideBeans().forEach(item -> {
                        logger.info(item.toString());
                    });
                }

                List<RideCSV> optimisedRideBeans = rideFilter.filterRide(ride);
                if (Boolean.getBoolean(ConfigService.config.getProperty("debug"))) {
                    optimisedRideBeans.forEach(item -> logger.info(item.toString()));
                }
                ride.setRideBeans(optimisedRideBeans);
            }




            /*
             * All filters and chain on Ride data must be implemented here.
             */
            /* 1. TODO: Empty Ride removal service */
            /* 2. TODO: None Bike data remove Service */

            /* 3. Matchig Service for ride */
            List mapMatchedRideBeans = mapMatchingService.matchToMap(ride.getRideBeans());
            ride.setMapMatchedRideBeans(mapMatchedRideBeans);
            /* 4. Distance Service.*/
            ride.setDistance(mapMatchingService.getCurrentRouteDistance());
            ride.setDuration(mapMatchingService.getCurrentRouteDuration());

            /**
             * End of filters
             */

            /*
             * Each ride file/data is ready for db and gets inserted in db.
             * Do not change.
             */
            dbService.getRidesCollection().insertOne(ride.toDocumentObject());

        } catch (Exception e) {
            logger.error(e);
        }
    }

    private void incidentParse(Ride ride, StringBuilder incidentContent, Path path) {

        try (Scanner scanner = new Scanner(incidentContent.toString())) {
            String firstLine = scanner.nextLine();
            String[] arrOfStr = firstLine.split(FILEVERSIONSPLITTER);

            StringBuilder incidentCSVwithHeader = new StringBuilder();
            while (scanner.hasNextLine()) {
                String currentLine = scanner.nextLine();
                if (!currentLine.isEmpty()) {
                    incidentCSVwithHeader.append(currentLine).append("\r\n");
                }
            }

            ColumnPositionMappingStrategy<IncidentCSV> strategy = new ColumnPositionMappingStrategy<>();
            strategy.setType(IncidentCSV.class);
            String[] memberFieldsToBindTo = {"key", "lat", "lon", "ts", "bike", "childCheckBox", "trailerCheckBox", "pLoc", "incident", "i1", "i2", "i3", "i4", "i5", "i6", "i7", "i8", "i9", "scary", "desc", "i10"};
            strategy.setColumnMapping(memberFieldsToBindTo);

            List<IncidentCSV> incidentBeans = new CsvToBeanBuilder<IncidentCSV>(new StringReader(incidentCSVwithHeader.toString()))
                    .withMappingStrategy(strategy)
                    .withSkipLines(1)
                    .withIgnoreLeadingWhiteSpace(true)
                    .build().parse();
            incidentBeans.forEach(item -> {
                item.setFileId(path.getFileName().toString());
                item.setAppVersion(arrOfStr[0]);
                item.setFileVersion(Integer.parseInt(arrOfStr[1]));
                item.setRegion(this.region);
            });
            ride.setIncidents(incidentBeans);
        } catch (Exception e) {
            logger.error(e);
        }
    }

    private void rideParse(Ride ride, StringBuilder rideContent, Path path) {
        try (Scanner scanner = new Scanner(rideContent.toString())) {
            scanner.nextLine();
            String fileAppLine = scanner.nextLine();
            String[] arrOfStr = fileAppLine.split(FILEVERSIONSPLITTER);

            StringBuilder rideCSVwithHeader = new StringBuilder();
            while (scanner.hasNextLine()) {
                String currentLine = scanner.nextLine();
                /*
                 * add ride information only if it has location data.
                 */
                if (currentLine.trim().length() > 0 && !currentLine.isEmpty() && !currentLine.trim().startsWith(",")) {
                    rideCSVwithHeader.append(currentLine.trim()).append("\r\n");
                }
            }

            List<RideCSV> rideBeans = new CsvToBeanBuilder<RideCSV>(new StringReader(rideCSVwithHeader.toString().trim()))
                    .withType(RideCSV.class).build().parse();

            rideBeans.forEach(item -> {
                item.setFileId(path.getFileName().toString());
                item.setAppVersion(arrOfStr[0]);
                item.setFileVersion(Integer.parseInt(arrOfStr[1]));
            });
            ride.setRideBeans(rideBeans);

        } catch (Exception e) {
            logger.error(e);
        }
    }
}
