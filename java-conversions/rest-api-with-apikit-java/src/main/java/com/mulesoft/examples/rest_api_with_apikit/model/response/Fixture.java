/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.model.response;

import java.util.List;

import jakarta.xml.bind.annotation.XmlElement;

public class Fixture {
    private List<Match> fixture;

    @XmlElement(name = "fixture")
    public List<Match> getFixture() {
        return fixture;
    }

    public void setFixture(List<Match> fixture) {
        this.fixture = fixture;
    }

}
