package com.simra.app.csvimporter.model;

import com.mongodb.client.model.geojson.LineString;
import com.mongodb.client.model.geojson.Position;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "rides")
public class RideEntity extends ApplicationFileVersion {

    @Id
    private String id;

    private String region;

    private LineString location;

    private LineString locationMapMatched;

    private List tsMapMatched;

    private List ts;

    private Float distance;

    private Long duration;

    private Date addedAt;

    private String weekday;

    private int minuteOfDay;

    private long timeStamp;

    public Date getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(Date addedAt) {
        this.addedAt = addedAt;
    }

    public LineString getLocation() {
        return location;
    }

    public void setLocation(LineString location) {
        this.location = location;
    }

    public List getTs() {
        return ts;
    }

    public void setTs(List ts) {
        this.ts = ts;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Float getDistance() {
        return distance;
    }

    public void setDistance(Float distance) {
        this.distance = distance;
    }

    public Long getDuration() {
        return duration;
    }

    public void setDuration(Long duration) {
        this.duration = duration;
    }

    public LineString getLocationMapMatched() {
        return locationMapMatched;
    }

    public void setLocationMapMatched(LineString locationMapMatched) {
        this.locationMapMatched = locationMapMatched;
    }

    public List getTsMapMatched() {
        return tsMapMatched;
    }

    public void setTsMapMatched(List tsMapMatched) {
        this.tsMapMatched = tsMapMatched;
    }

    public String getWeekday() {
        return weekday;
    }

    public void setWeekday(String weekday) {
        this.weekday = weekday;
    }

    public int getMinuteOfDay() {
        return minuteOfDay;
    }

    public void setMinuteOfDay(int minuteOfDay) {
        this.minuteOfDay = minuteOfDay;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }


    public void setMapMatchedRideBeans(List<RideCSV> mapMatchedRideBeans) {
        ArrayList<Position> coordinates = new ArrayList<>();
        mapMatchedRideBeans.forEach(ride -> {
            List<Double> places = Arrays.asList(round(Double.parseDouble(ride.getLon()), 5), round(Double.parseDouble(ride.getLat()), 5));
            Position pos = new Position(places);
            coordinates.add(pos);
        });
        LineString coordinatesMulti = new LineString(coordinates);
        this.setLocationMapMatched(coordinatesMulti);
        ts = new ArrayList<>();
        mapMatchedRideBeans.forEach(ride -> ts.add((ride).getTimeStamp()));
        this.setTsMapMatched(ts);
    }

    private static double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();

        BigDecimal bd = BigDecimal.valueOf(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }
}
