package dev.example.flighttracker.service;

import dev.example.flighttracker.model.GeoLocationResponse;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
//import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FlightAssistantService {
    private static final Logger logger = LoggerFactory.getLogger(FlightAssistantService.class);
    private static final int DEFAULT_RADIUS_NM = 20;

    private final FlightAIService flightAIService;
    private final GeoLocationService geoLocationService;

    public FlightAssistantService(
            //@Value("${spring.ai.anthropic.key}") String apiKey,
            //@Value("${spring.ai.chat.model:claude-3-5-sonnet-latest}") String modelName,
            //@Value("${spring.ai.options.temperature:0.2}") Double temperature,
            @Value("${spring.azure-ai.azure-openai-endpoint}") String azureOpenAIEndpoint,
            @Value("${spring.azure-ai.azure-openai-key}") String azureOpenAIKey,
            @Value("${spring.azure-ai.azure-deployment-name}") String azureDeploymentName,
            FlightDataFunctions flightDataFunctions,
            GeoLocationService geoLocationService) {

        this.geoLocationService = geoLocationService;

        /*
        var chatLanguageModel = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .build();
         */

        AzureOpenAiChatModel azureChatLanguageModel = AzureOpenAiChatModel.builder()
                .endpoint(azureOpenAIEndpoint)
                .apiKey(azureOpenAIKey)
                .deploymentName(azureDeploymentName)
                .maxTokens(16000)
                .build();

        flightAIService = AiServices.builder(FlightAIService.class)
                .chatModel(azureChatLanguageModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .tools(flightDataFunctions)
                .build();
    }

    public String processQuery(String userQuery, String clientIp) {
        try {
            String enrichedQuery = enrichWithLocation(userQuery, clientIp);
            return flightAIService.processQuery(enrichedQuery);
        } catch (Exception e) {
            logger.error("error processing query", e);
            return "I'm currently experiencing technical difficulties. Please try again later.";
        }
    }

    private String enrichWithLocation(String userQuery, String clientIp) {
        GeoLocationResponse location = geoLocationService.lookup(clientIp);
        if (location == null) {
            return userQuery;
        }

        logger.info("Resolved client ip {} to location {}, {} ({}, {})",
                clientIp, location.lat(), location.lon(), location.city(), location.country());

        String locationContext = String.format(
                "[User location context: latitude=%.4f, longitude=%.4f, city=%s, country=%s. " +
                        "When the user asks about aircraft \"near me\", \"around here\", \"nearby\", " +
                        "or \"my current location\", call findAircraftNearLocation with these exact " +
                        "coordinates and a radius of %d nautical miles.]%n%n",
                location.lat(),
                location.lon(),
                location.city() != null ? location.city() : "unknown",
                location.country() != null ? location.country() : "unknown",
                DEFAULT_RADIUS_NM);

        return locationContext + userQuery;
    }
}
