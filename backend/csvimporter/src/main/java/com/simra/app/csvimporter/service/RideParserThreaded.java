package com.simra.app.csvimporter.service;

import com.mongodb.client.model.geojson.LineString;
import com.mongodb.client.model.geojson.Position;
import com.opencsv.bean.ColumnPositionMappingStrategy;
import com.opencsv.bean.CsvToBeanBuilder;
import com.simra.app.csvimporter.controller.LegRepository;
import com.simra.app.csvimporter.controller.RideRepository;
import com.simra.app.csvimporter.filter.MapMatchingService;
import com.simra.app.csvimporter.filter.RideSmoother;
import com.simra.app.csvimporter.model.LegEntity;
import com.simra.app.csvimporter.model.RideCSV;
import com.simra.app.csvimporter.model.RideEntity;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Point;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

public class RideParserThreaded implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(RideParserThreaded.class);


    private String fileName;

    private String csvString;

    private RideRepository rideRepository;

    private LegRepository legRepository;

    private RideSmoother rideSmoother;

    private MapMatchingService mapMatchingService;

    private LegPartitioningService legPartitioningService;

    private String region;

    private Integer minRideDistance;

    private Integer minRideDuration;

    private Integer maxRideAverageSpeed;

    private Integer minDistanceToCoverByUserIn5Min;

    public RideParserThreaded(
            String fileName,
            RideRepository rideRepository,
            LegRepository legRepository,
            Float minAccuracy,
            double rdpEpsilon,
            MapMatchingService mapMatchingService,
            LegPartitioningService legPartitioningService,
            String csvString,
            String region,
            Integer minRideDistance,
            Integer minRideDuration,
            Integer maxRideAverageSpeed,
            Integer minDistanceToCoverByUserIn5Min) {

        this.fileName = fileName;
        this.csvString = csvString;
        this.rideRepository = rideRepository;
        this.legRepository = legRepository;
        this.rideSmoother = new RideSmoother(minAccuracy, rdpEpsilon);
        this.mapMatchingService = mapMatchingService;
        this.legPartitioningService = legPartitioningService;
        this.region = region;
        this.minRideDistance = minRideDistance;
        this.minRideDuration = minRideDuration * 60 * 1000; // minutes to millis
        this.maxRideAverageSpeed = maxRideAverageSpeed;
        this.minDistanceToCoverByUserIn5Min = minDistanceToCoverByUserIn5Min;
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
            ColumnPositionMappingStrategy<RideEntity> strategy = new ColumnPositionMappingStrategy<>();
            strategy.setType(RideEntity.class);
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

            // filter short Distance Rides
            if (routeDistance < minRideDistance) {
                LOG.info(fileName + " filtered due to routeDistance = " + routeDistance + "m");
                return;
            }

            // filter short Duration Rides
            if (routeDuration < minRideDuration) {
                LOG.info(fileName + " filtered due to routeDuration = " + (routeDuration / 60000) + "min");
                return;
            }

            // filter high average speed
            double averageSpeed = Utils.calcAverageSpeed(routeDistance, routeDuration);
            if (averageSpeed > maxRideAverageSpeed) {
                LOG.info(fileName + " filtered due to averageSpeed = " + averageSpeed + "km/h");
                return;
            }

            // filter rides that user did not stop
            if (isUserForgotToStopRecording(optimisedRideBeans)) {
                LOG.info(fileName + " filtered due to User forgot to stop recording");
                //return;
            }

            /*
             * All filters related to csv parsed data must end before this
             */

            RideEntity rideEntity = getRideEntity(arrOfStr, optimisedRideBeans);
            rideEntity.setRegion(region);
            /*
             * needed modified entity properties must insert here.
             */
            rideEntity.setMapMatchedRideBeans(mapMatchedRideBeans);
            rideEntity.setDistance(mapMatchingService.getCurrentRouteDistance());
            rideEntity.setDuration(mapMatchingService.getCurrentRouteDuration());

            rideEntity.setMinuteOfDay(Utils.getMinuteOfDay(rideEntity.getTimeStamp()));
            rideEntity.setWeekday(Utils.getWeekday(rideEntity.getTimeStamp()));

            List<LegEntity> rideResources = new ArrayList<>();
            //List<RideEntity> rideEntities = rideRepository.findByLocationNear(point, maxDistance);
            //rideResources = mapRideEntityToResource(rideEntities, rideResources, true);

            RideEntity ride1 = new RideEntity();
            RideEntity ride2 = new RideEntity();
            RideEntity ride3 = new RideEntity();

            Point[] array1 = {
                    new Point(0d, 1d),
                    new Point(1d, 1d),
                    new Point(2d, 1d),
                    new Point(3d, 1d),
                    new Point(4d, 1d),
                    new Point(5d, 1d),
                    new Point(6d, 1d)
            };
            LineString geoJson1 = new LineString(Arrays.stream(array1).map(it -> new Position(Arrays.asList(it.getX(), it.getY()))).collect(Collectors.toList()));


            Point[] array2 = {
                    new Point(0d, 2d),
                    new Point(1d, 1d),
                    new Point(2d, 1d),
                    new Point(3d, 1d),
                    new Point(4d, 1d),
                    new Point(5d, 2d),
                    new Point(6d, 2d)
            };
            LineString geoJson2 = new LineString(Arrays.stream(array2).map(it -> new Position(Arrays.asList(it.getX(), it.getY()))).collect(Collectors.toList()));


            Point[] array3 = {
                    new Point(0d, 0d),
                    new Point(1d, 0d),
                    new Point(2d, 1d),
                    new Point(3d, 1d),
                    new Point(4d, 1d),
                    new Point(5d, 1d),
                    new Point(6d, 0d)
            };
            LineString geoJson3 = new LineString(Arrays.stream(array3).map(it -> new Position(Arrays.asList(it.getX(), it.getY()))).collect(Collectors.toList()));
            ride1.setLocationMapMatched(geoJson1);
            ride2.setLocationMapMatched(geoJson2);
            ride3.setLocationMapMatched(geoJson3);

            ride1.setFileId("File1");
            ride2.setFileId("File2");
            ride3.setFileId("File3");


            legPartitioningService.mergeRideIntoLegs(ride1);
            legPartitioningService.mergeRideIntoLegs(ride2);
            legPartitioningService.mergeRideIntoLegs(ride3);


            LegEntity legEntity = legPartitioningService.parseRideToLeg(rideEntity);

//            legEntity.setGeometry(new GeoJsonLineString(legEntity.getGeometry().getCoordinates().subList(2,10)));


//            legPartitioningService.mergeRideIntoLegs(rideEntity);

            // legRepository.save(legEntity);
            rideRepository.save(rideEntity);

        } catch (Exception e) {
            LOG.error(String.valueOf(e));
        }
        LOG.info("Ride parser complete {} ", this.fileName);


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

        for (int i = 0; i < rideCSVList.size(); i++) {
            double cumulatedDistance = 0d;
            for (int j = 0; j < 100; j++) {
                try {
                    cumulatedDistance += Utils.calcDistance(
                            Double.parseDouble(rideCSVList.get(i + j).getLat()),
                            Double.parseDouble(rideCSVList.get(i + j).getLon()),
                            Double.parseDouble(rideCSVList.get(i + j + 1).getLat()),
                            Double.parseDouble(rideCSVList.get(i + j + 1).getLon()));
                } catch (IndexOutOfBoundsException e) {
                    break;
                }
            }
            if (cumulatedDistance < minDistanceToCoverByUserIn5Min) {
                return true;
            }
        }
        return false;
    }
}
