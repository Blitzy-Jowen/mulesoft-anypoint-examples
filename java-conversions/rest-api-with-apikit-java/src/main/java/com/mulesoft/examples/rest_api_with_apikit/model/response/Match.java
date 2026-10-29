/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.model.response;

import java.util.Date;

import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@JsonAutoDetect
@XmlRootElement(namespace = "http://mulesoft.com/schemas/soccer")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Match {

    private String homeTeam;
    private String awayTeam;

    @JsonSerialize(using = com.mulesoft.examples.rest_api_with_apikit.mapper.JsonDateSerializer.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Date date;
    private Integer homeTeamScore;
    private Integer awayTeamScore;

    @JsonProperty
    @XmlElement(required = true, namespace = "http://mulesoft.com/schemas/soccer")
    public String getHomeTeam()
    {
        return homeTeam;
    }

    public void setHomeTeam(String homeTeam)
    {
        this.homeTeam = homeTeam;
    }

    @XmlElement(required = true, namespace = "http://mulesoft.com/schemas/soccer")
    public String getAwayTeam()
    {
        return awayTeam;
    }

    public void setAwayTeam(String awayTeam)
    {
        this.awayTeam = awayTeam;
    }

    @XmlElement(required = true, namespace = "http://mulesoft.com/schemas/soccer")
    public Date getDate()
    {
        return date;
    }

    public void setDate(Date date)
    {
        this.date = date;
    }

    @XmlElement(required = true, namespace = "http://mulesoft.com/schemas/soccer")
    public Integer getHomeTeamScore()
    {
        return homeTeamScore;
    }

    public void setHomeTeamScore(Integer homeTeamScore)
    {
        this.homeTeamScore = homeTeamScore;
    }

    @XmlElement(required = true, namespace = "http://mulesoft.com/schemas/soccer")
    public Integer getAwayTeamScore()
    {
        return awayTeamScore;
    }

    public void setAwayTeamScore(Integer awayTeamScore)
    {
        this.awayTeamScore = awayTeamScore;
    }

}
