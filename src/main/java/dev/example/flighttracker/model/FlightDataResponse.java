package dev.example.flighttracker.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightDataResponse(
        @JsonProperty("aircraft")
        @JsonAlias("ac")
        List<Aircraft> aircraft,

        @JsonProperty("resultCount")
        @JsonAlias("total")
        Integer total,

        @JsonProperty("now")
        Long timestamp,

        @JsonProperty("ptime")
        Long parseTime,

        @JsonProperty("msg")
        String message,

        @JsonProperty("ctime")
        Long creationTime
) {
    /**
     * Returns the list of aircraft, or an empty list if the source is null.
     * This prevents NullPointerExceptions downstream.
     */
    public List<Aircraft> getAircraft() {
        return aircraft != null ? aircraft : List.of();
    }
}