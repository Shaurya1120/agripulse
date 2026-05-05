package com.agripulse.app.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// @Entity tells JPA that this class should be mapped to a database table.
@Entity
// @Table lets us control the table name instead of relying on a default guess.
@Table(name = "risk_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RiskReport {

    // @Id marks the primary key column of the table.
    @Id
    // @GeneratedValue tells the database to automatically generate the id value.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String cropName;
    private String region;
    private String riskLevel;
    private String mitigationStrategy;
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
