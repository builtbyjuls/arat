package com.builtbyjuls.arat.architecture.fixture.cycle.first.api;

import com.builtbyjuls.arat.architecture.fixture.cycle.second.api.SecondApi;

public final class FirstApi {

    public SecondApi secondApi() {
        return new SecondApi();
    }
}
