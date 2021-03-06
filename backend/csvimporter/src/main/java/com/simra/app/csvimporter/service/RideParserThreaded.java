package com.simra.app.csvimporter.service;

import com.mongodb.client.model.geojson.LineString;
import com.mongodb.client.model.geojson.Position;
import com.opencsv.bean.ColumnPositionMappingStrategy;
import com.opencsv.bean.CsvToBeanBuilder;
import com.simra.app.csvimporter.filter.RideSmoother;
import com.simra.app.csvimporter.mapmatching.MapMatchingService;
import com.simra.app.csvimporter.model.RideCSV;
import com.simra.app.csvimporter.model.RideEntity;
import com.simra.app.csvimporter.repository.IncidentRepository;
import com.simra.app.csvimporter.repository.RideRepository;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.ExecutorService;

public class RideParserThreaded implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(RideParserThreaded.class);


    private String fileName;

    private String csvString;

    private RideRepository rideRepository;

    private RideSmoother rideSmoother;

    private MapMatchingService mapMatchingService;

    private LegPartitioningService legPartitioningService;

    private String region;

    private Integer minRideDistance;

    private Integer minRideDuration;

    private Integer maxRideAverageSpeed;

    private Integer minDistanceToCoverByUserIn5Min;

    private Map<String, Object> paramsIncidentParser;

    public RideParserThreaded(
            String fileName,
            RideRepository rideRepository,
            Float minAccuracy,
            double rdpEpsilon,
            MapMatchingService mapMatchingService,
            LegPartitioningService legPartitioningService,
            String csvString,
            String region,
            Integer minRideDistance,
            Integer minRideDuration,
            Integer maxRideAverageSpeed,
            Integer minDistanceToCoverByUserIn5Min,
            Map<String, Object> paramsIncidentParser) {

        this.fileName = fileName;
        this.csvString = csvString;
        this.rideRepository = rideRepository;
        this.rideSmoother = new RideSmoother(minAccuracy, rdpEpsilon);
        this.mapMatchingService = mapMatchingService;
        this.legPartitioningService = legPartitioningService;
        this.region = region;
        this.minRideDistance = minRideDistance;
        this.minRideDuration = minRideDuration * 60 * 1000; // minutes to millis
        this.maxRideAverageSpeed = maxRideAverageSpeed;
        this.minDistanceToCoverByUserIn5Min = minDistanceToCoverByUserIn5Min;
        this.paramsIncidentParser = paramsIncidentParser;
    }


    @Override
    public void run() {
        LOG.info("Ride parser running {}", this.fileName);
        try (BufferedReader reader = new BufferedReader(new StringReader(this.csvString))) {
            String line = reader.readLine();
            String[] arrOfStr = line.split("#");

            while (line != null) {
                line = reader.readLine();
                if (line.contains("==")) {
                    break;
                }
            }
            StringBuilder rideCSVwithHeader = new StringBuilder();
            String currentLine = reader.readLine();
            while (currentLine != null) {
                currentLine = reader.readLine();
                /*
                 * add ride information only if it has location data.
                 */
                if (currentLine == null) {
                    break;
                }
                if (currentLine.trim().length() > 0 && !currentLine.isEmpty() && !currentLine.trim().startsWith(",")) {
                    rideCSVwithHeader.append(currentLine.trim()).append("\r\n");
                }
            }
            ColumnPositionMappingStrategy<RideCSV> strategy = new ColumnPositionMappingStrategy<>();
            strategy.setType(RideCSV.class);
            String[] memberFieldsToBindTo = {"lat", "lon", "X", "Y", "Z", "timeStamp", "acc", "a", "b", "c"};
            strategy.setColumnMapping(memberFieldsToBindTo);

            List<RideCSV> rideBeans = new CsvToBeanBuilder<RideCSV>(new StringReader(rideCSVwithHeader.toString().trim()))
                    .withMappingStrategy(strategy)
                    .withSkipLines(1)
                    .withIgnoreLeadingWhiteSpace(true)
                    .build().parse();

            /*
             * ALl filters before DB Entity must chain here.
             */
            // Acc & RDP Filter
            List<RideCSV> optimisedRideBeans = this.rideSmoother.smoothRide(rideBeans);
            // Map Matching
            List<RideCSV> mapMatchedRideBeans = mapMatchingService.matchToMap(optimisedRideBeans);

            Float routeDistance = mapMatchingService.getCurrentRouteDistance();
            Long routeDuration = mapMatchingService.getCurrentRouteDuration();

            long startTimeStamp = rideBeans.get(0).getTimeStamp();

            // filter short Distance Rides
            if (routeDistance < minRideDistance) {
                LOG.info("{} filtered due to routeDistance = {} m", fileName, routeDistance);
                return;
            }

            // filter short Duration Rides
            if (routeDuration < minRideDuration) {
                LOG.info("{} filtered due to routeDuration = {} min", fileName, (routeDuration / 60000));
                return;
            }

            // filter high average speed
            double averageSpeed = Utils.calcAverageSpeed(routeDistance, routeDuration);
            if (averageSpeed > maxRideAverageSpeed) {
                LOG.info("{} filtered due to averageSpeed = {} km/h", fileName, averageSpeed);
                return;
            }

            // filter rides that user did not stop
            if (isUserForgotToStopRecording(optimisedRideBeans)) {
                LOG.info("{} filtered due to User forgot to stop recording", fileName);
                return;
            }

            /*
             * All filters related to csv parsed data must end before this
             */

            RideEntity rideEntity = getRideEntity(arrOfStr, optimisedRideBeans);
            rideEntity.setRegion(region);
            /*
             * needed modified entity properties must start here.
             */
            rideEntity.setMapMatchedRideBeans(mapMatchedRideBeans);
            rideEntity.setDistance(mapMatchingService.getCurrentRouteDistance());
            rideEntity.setDuration(mapMatchingService.getCurrentRouteDuration());
            rideEntity.setTimeStamp(startTimeStamp);
            rideEntity.setMinuteOfDay(Utils.getMinuteOfDay(rideEntity.getTimeStamp()));
            rideEntity.setWeekday(Utils.getWeekday(rideEntity.getTimeStamp()));


            legPartitioningService.mergeRideIntoLegs(rideEntity);

            // parse incident before saving ride.
            this.runIncidentParserThread(rideEntity);
            rideRepository.save(rideEntity);

        } catch (Exception e) {
            LOG.error(String.valueOf(e));
        }
        LOG.info("Ride parser complete {} ", this.fileName);


    }

    private void runIncidentParserThread(RideEntity rideEntity) {
        // incidents are parsed subsequently to ride
        IncidentRepository incidentRepository = (IncidentRepository) this.paramsIncidentParser.get("incidentRepository");
        ExecutorService incidentExecutor = (ExecutorService) this.paramsIncidentParser.get("executor");
        IncidentParserThreaded incidentParserThreaded = new IncidentParserThreaded(this.fileName, incidentRepository, this.csvString, this.region, rideEntity);
        incidentExecutor.execute(incidentParserThreaded);
    }

    @NotNull
    private RideEntity getRideEntity(String[] arrOfStr, List<RideCSV> rideBeans) {
        RideEntity rideEntity = new RideEntity();
        rideEntity.setId(this.fileName);
        rideEntity.setFileId(this.fileName);
        rideEntity.setAppVersion(arrOfStr[0]);
        rideEntity.setFileVersion(Integer.parseInt(arrOfStr[1]));

        ArrayList<Position> coordinates = new ArrayList<>();

        rideBeans.forEach(ride -> {
            List<Double> places = Arrays.asList(Double.parseDouble(ride.getLon()), Double.parseDouble(ride.getLat()));
            Position pos = new Position(places);
            coordinates.add(pos);
        });
        LineString coordinatesMulti = new LineString(coordinates);
        rideEntity.setLocation(coordinatesMulti);

        ArrayList<Long> ts = new ArrayList<>();
        rideBeans.forEach(ride -> ts.add((ride).getTimeStamp()));
        rideEntity.setTs(ts);

        rideEntity.setAddedAt(new Date());
        return rideEntity;
    }

    /*
     * heuristic approach
     *
     * ride will be classified as 'forgot to stop' when User does not
     * exceed ${min_distance_to_cover_by_user_in_5_min} in 5min (300sec) (300*6000millis)
     *
     * 5min in 3sec steps = 100steps
     */
    private boolean isUserForgotToStopRecording(List<RideCSV> rideCSVList) {
        outerLoop:
        for (int i = 0; i < rideCSVList.size(); i++) {
            double cumulatedDistance = 0d;
            for (int j = 0; j < 100; j++) { // 100 steps = 5 min
                try {
                    cumulatedDistance = cumulatedDistance + Utils.calcDistance(
                            Double.parseDouble(rideCSVList.get(i + j).getLat()),
                            Double.parseDouble(rideCSVList.get(i + j).getLon()),
                            Double.parseDouble(rideCSVList.get(i + j + 1).getLat()),
                            Double.parseDouble(rideCSVList.get(i + j + 1).getLon()));
                } catch (IndexOutOfBoundsException e) {
                    break outerLoop;
                }
            }
            if (cumulatedDistance < minDistanceToCoverByUserIn5Min) {
                return true;
            }
        }
        return false;
    }
}
