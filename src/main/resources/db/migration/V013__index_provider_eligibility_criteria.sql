CREATE INDEX provider_supported_category_eligibility_lookup_idx
    ON provider_supported_category (category, provider_id);

CREATE INDEX provider_service_area_eligibility_lookup_idx
    ON provider_service_area (area_code, provider_id);
