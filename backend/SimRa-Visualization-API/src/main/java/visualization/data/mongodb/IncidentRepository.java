package visualization.data.mongodb;

import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
import org.springframework.data.mongodb.repository.MongoRepository;
import visualization.data.mongodb.entities.IncidentEntity;

import java.util.List;
import java.util.Optional;

public interface IncidentRepository extends MongoRepository<IncidentEntity, IncidentEntity.CompositeKey>, IncidentRepositoryCustom {

    List<IncidentEntity> findByFileId(String rideId);

    Optional<IncidentEntity> findById(IncidentEntity.CompositeKey id);

    List<IncidentEntity> findByLocationMapMatchedNear(GeoJsonPoint coordinates, int maxDistance);

    List<IncidentEntity> findByLocationMapMatchedWithin(GeoJsonPolygon polygon);

}
