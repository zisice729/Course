package com.wylie.registration.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.*;

@Component
public class Settings {
    @Value("${app.profile}") public String profile;
    @Value("${app.fixed-clock:}") public String fixedClock;
    @Value("${app.billing-url}") public String billingUrl;
    @Value("${app.catalog-url}") public String catalogUrl;
    @Value("${app.mock-token}") public String mockToken;
    public Instant now(){return fixedClock==null||fixedClock.isBlank()?Instant.now():Instant.parse(fixedClock);}
    public LocalDate today(){return now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();}
    public boolean test(){return "test".equals(profile);}
}
