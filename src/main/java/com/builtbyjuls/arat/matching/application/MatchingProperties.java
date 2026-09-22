package com.builtbyjuls.arat.matching.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("arat.matching")
@Validated
public class MatchingProperties {

    @Min(1)
    @Max(500)
    private int recipientCap = 100;

    public int recipientCap() {
        return recipientCap;
    }

    public void setRecipientCap(int recipientCap) {
        this.recipientCap = recipientCap;
    }
}
