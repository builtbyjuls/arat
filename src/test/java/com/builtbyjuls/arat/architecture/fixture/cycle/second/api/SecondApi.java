package com.builtbyjuls.arat.architecture.fixture.cycle.second.api;

import com.builtbyjuls.arat.architecture.fixture.cycle.first.api.FirstApi;

public final class SecondApi {

    public FirstApi firstApi() {
        return new FirstApi();
    }
}
