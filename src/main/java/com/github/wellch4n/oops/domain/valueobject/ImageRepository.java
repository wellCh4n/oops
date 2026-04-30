package com.github.wellch4n.oops.domain.valueobject;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.wellch4n.oops.converter.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;

@Data
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
public class ImageRepository {

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Column(name = "image_repository_url")
    private String url;

    @Column(name = "image_repository_username")
    private String username;

    @Column(name = "image_repository_password")
    @Convert(converter = EncryptedStringConverter.class)
    private String password;

    public static ImageRepository of(String url, String username, String password) {
        return new ImageRepository(url, username, password);
    }

    @JsonIgnore
    public boolean isValid() {
        if (StringUtils.isAnyEmpty(this.url, this.username, this.password)) return false;

        HttpUrl httpUrl = HttpUrl.parse(this.url);
        if (httpUrl == null) return false;

        HttpUrl rootUrl = httpUrl.resolve("/");
        if (rootUrl == null) return false;

        String credential = Credentials.basic(this.username, this.password);
        Request request = new Request.Builder()
                .url(rootUrl)
                .header("Authorization", credential)
                .get()
                .build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            return response.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }
}
