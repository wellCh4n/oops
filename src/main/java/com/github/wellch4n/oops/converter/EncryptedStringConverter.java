package com.github.wellch4n.oops.converter;

import com.github.wellch4n.oops.utils.EncryptionUtils;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final Logger logger = LoggerFactory.getLogger(EncryptedStringConverter.class);

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        return EncryptionUtils.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return EncryptionUtils.decrypt(dbData);
        } catch (Exception e) {
            logger.warn("Decryption failed, returning plaintext fallback", e);
            return dbData;
        }
    }
}
