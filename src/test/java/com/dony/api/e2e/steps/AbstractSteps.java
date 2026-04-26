package com.dony.api.e2e.steps;

import com.dony.api.e2e.context.ScenarioContext;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.springframework.beans.factory.annotation.Autowired;

import static io.restassured.RestAssured.given;

public abstract class AbstractSteps {

    @Autowired
    protected ScenarioContext ctx;

    /** Authenticated request with X-Test-UID + X-Test-Roles headers. */
    protected RequestSpecification asCurrentUser() {
        var spec = given().contentType(ContentType.JSON);
        if (ctx.getCurrentUid() != null) {
            spec = spec.header("X-Test-UID", ctx.getCurrentUid());
            if (ctx.getCurrentRoles() != null) {
                spec = spec.header("X-Test-Roles", ctx.getCurrentRoles());
            }
        }
        return spec;
    }

    /** Unauthenticated request (public endpoints). */
    protected RequestSpecification asPublic() {
        return given().contentType(ContentType.JSON);
    }

    protected Response lastResponse() {
        return ctx.getLastResponse();
    }

    protected void store(Response response) {
        ctx.setLastResponse(response);
    }
}
