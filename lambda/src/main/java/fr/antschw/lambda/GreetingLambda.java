package fr.antschw.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named("greeting")
public class GreetingLambda implements RequestHandler<InputObject, OutputObject> {

    @Inject
    GreetingService service;

    @Override
    public OutputObject handleRequest(InputObject input, Context context) {
        String requestId = context != null ? context.getAwsRequestId() : "local";
        return new OutputObject(service.greet(input.getName()), requestId);
    }
}
