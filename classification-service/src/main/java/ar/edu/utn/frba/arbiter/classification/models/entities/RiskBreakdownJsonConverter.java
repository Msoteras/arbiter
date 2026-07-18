package ar.edu.utn.frba.arbiter.classification.models.entities;

import ar.edu.utn.frba.arbiter.common.dto.RiskBreakdownItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * Serializes the risk breakdown to a JSON string in a {@code text} column — same convention as
 * {@link StringListJsonConverter}, no JSONB. {@code null} is preserved (not turned into an empty
 * list) so the "sin scorear" state survives the round-trip and isn't confused with a real score
 * that happened to have an empty breakdown.
 */
@Converter
public class RiskBreakdownJsonConverter implements AttributeConverter<List<RiskBreakdownItem>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<RiskBreakdownItem>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<RiskBreakdownItem> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize risk breakdown to JSON", e);
        }
    }

    @Override
    public List<RiskBreakdownItem> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not read risk breakdown JSON", e);
        }
    }
}
