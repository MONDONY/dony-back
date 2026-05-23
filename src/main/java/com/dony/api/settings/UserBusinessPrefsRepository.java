package com.dony.api.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface UserBusinessPrefsRepository extends JpaRepository<UserBusinessPrefsEntity, UUID> {}
