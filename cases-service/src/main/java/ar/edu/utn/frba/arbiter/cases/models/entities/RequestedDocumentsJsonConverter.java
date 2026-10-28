package ar.edu.utn.frba.arbiter.cases.models.entities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/** The column is NOT NULL with an empty-array default, so "nothing requested" is never null. */
@Converter
public class RequestedDocumentsJsonConverter implements AttributeConverter<List<RequestedDocument>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<RequestedDocument>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<RequestedDocument> attribute) {
        try {
            return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize requested documents to JSON", e);
        }
    }

    @Override
    public List<RequestedDocument> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(MAPPER.readValue(dbData, TYPE));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not read requested documents JSON", e);
        }
    }
}
