package oxchains.fabric.console.data;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import oxchains.fabric.console.domain.ChainCodeInfo;

import java.util.Optional;

/**
 * @author aiet
 */
@Repository
public interface ChaincodeRepo extends CrudRepository<ChainCodeInfo, String>{

    Optional<ChainCodeInfo> findByNameAndVersion(String name, String version);
}
