package oxchains.fabric.console.service;

import org.apache.commons.io.FileUtils;
import org.hyperledger.fabric.sdk.ChainCodeID;
import org.hyperledger.fabric.sdk.ChaincodeEndorsementPolicy;
import org.hyperledger.fabric.sdk.Peer;
import org.hyperledger.fabric.sdk.ProposalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import oxchains.fabric.console.data.ChaincodeRepo;
import oxchains.fabric.console.domain.ChainCodeInfo;
import oxchains.fabric.console.rest.common.QueryResult;
import oxchains.fabric.console.rest.common.TxPeerResult;
import oxchains.fabric.console.rest.common.TxResult;
import oxchains.fabric.sdk.FabricSDK;

import java.io.File;
import java.util.*;
import java.util.function.Function;

import static com.google.common.collect.Lists.newArrayList;
import static java.time.LocalDateTime.now;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;
import static java.util.Collections.emptyList;
import static java.util.Objects.nonNull;
import static java.util.Optional.empty;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.hyperledger.fabric.sdk.ChainCodeResponse.Status.SUCCESS;

/**
 * @author aiet
 */
@Service
public class ChaincodeService {

    private Logger LOG = LoggerFactory.getLogger(this.getClass());

    private FabricSDK fabricSDK;
    private ChaincodeRepo chaincodeRepo;

    public ChaincodeService(@Autowired FabricSDK fabricSDK, @Autowired ChaincodeRepo chaincodeRepo) {
        this.fabricSDK = fabricSDK;
        this.chaincodeRepo = chaincodeRepo;
    }

    @Value("${fabric.chaincode.path}") private String path;
    @Value("${fabric.tx.timeout}") private int txTimeout;

    public boolean cacheChaincode(String name, String version, String lang, MultipartFile file) {
        try {
            File chaincodePath = new File(String.format("%s/src/%s-%s", path, name, version));
            if (chaincodePath.exists()) {
                LOG.warn("chaincode {}-{} already exists", name, version);
                return false;
            } else FileUtils.forceMkdir(chaincodePath);

            File target = new File(chaincodePath.getPath() + String.format("/%s-%s-%s.go", name, version, now().format(ISO_LOCAL_DATE_TIME)));
            if (target.exists()) {
                LOG.warn("target cache file {} already exists", target.getPath());
                return false;
            }
            file.transferTo(target);
            chaincodeRepo.save(new ChainCodeInfo(name, version, lang, target
              .getPath()
              .replace(path, "")));
            LOG.info("chaincode {}-{} written in {} cached to file {}", name, version, lang, target.getPath());
            return true;
        } catch (Exception e) {
            LOG.error("failed to cache chaincode {}-{}", name, version, e);
        }
        return false;
    }

    private String chaincodePath(String name, String version) {
        return name + "-" + version;
    }

    public List<TxResult> installCCOnPeer(String name, String version, String lang, String... peers) {
        ChainCodeID chaincode = ChainCodeID
          .newBuilder()
          .setName(name)
          .setVersion(version)
          .setPath(chaincodePath(name, version))
          .build();

        if (noPeersYet()) return emptyList();

        try {
            Optional<ChainCodeInfo> chainCodeInfoOptional = chaincodeRepo.findByNameAndVersion(name, version);
            if(!chainCodeInfoOptional.isPresent()) return emptyList();
            ChainCodeInfo chainCodeInfo = chainCodeInfoOptional.get();
            Set<String> installedPeers = chainCodeInfo.getInstalled();
            List<String> peers2Install = Arrays.asList(peers);
            installedPeers.retainAll(peers2Install);
            List<Peer> peerList = peers2Install
              .stream()
              .map(p -> fabricSDK
                .getPeer(p)
                .orElse(null))
              .filter(Objects::nonNull)
              .filter(p -> !installedPeers.contains(p.getName()))
              .collect(toList());

            List<TxResult> list = installedPeers
              .stream()
              .map(installedPeer -> new TxResult<>(null, installedPeer, 1))
              .collect(toList());

            if (!peerList.isEmpty()) {
                List<ProposalResponse> installResult = fabricSDK.installChaincodeOnPeer(chaincode, path, lang, peerList);
                installResult
                  .stream()
                  .map(resp -> {
                      if (resp.getStatus() == SUCCESS) {
                          chainCodeInfo.addInstalled(resp
                            .getPeer()
                            .getName());
                          LOG.info("chaincode {}-{} installed on peer", name, version, resp
                            .getPeer()
                            .getName());
                      }
                      return resp;
                  })
                  .map(RESPONSE2TXRESULT_FUNC)
                  .forEach(list::add);
                chaincodeRepo.save(chainCodeInfo);
            }
            return list;

        } catch (Exception e) {
            LOG.error("failed to update install status of chaincode {}-{}", name, version, e);
        }
        return emptyList();
    }

    public Optional<TxPeerResult> instantiate(String name, String version, MultipartFile endorsement, String... params) {
        ChainCodeID chaincode = ChainCodeID
          .newBuilder()
          .setName(name)
          .setVersion(version)
          .setPath(chaincodePath(name, version))
          .build();

        if (noPeersYet()) return empty();

        try {
            Optional<ChainCodeInfo> chainCodeInfoOptional = chaincodeRepo.findByNameAndVersion(name, version);
            if(!chainCodeInfoOptional.isPresent()) return empty();
            ChainCodeInfo chainCodeInfo = chainCodeInfoOptional.get();
            File dir = new File(String.format("%s/endorsement/%s", path, name));
            if (!dir.exists()) FileUtils.forceMkdir(dir);
            File endorsementYaml = new File(String.format("%s/endorsement/%s/%s-%s-(%s).yaml", path, name, name, version, now().toString()));
            endorsement.transferTo(endorsementYaml);

            ChaincodeEndorsementPolicy chaincodeEndorsementPolicy = new ChaincodeEndorsementPolicy();
            chaincodeEndorsementPolicy.fromYamlFile(endorsementYaml);

            LOG.info("instantiating chaincode {}-{} with endorsement {}", name, version, endorsementYaml.getPath());

            return Optional.of(fabricSDK
              .instantiateChaincode(chaincode, chaincodeEndorsementPolicy, params)
              .thenApplyAsync(RESPONSE2TXPEERRESULT_FUNC)
              .thenApplyAsync(txPeerResult -> {
                  if (txPeerResult.getSuccess() == 1) {
                      chainCodeInfo.addInstantiated(txPeerResult.getPeer());
                      chaincodeRepo.save(chainCodeInfo);
                      LOG.info("chaincode {}-{} instantiated on peer {}", name, version, txPeerResult.getPeer());
                  }
                  return txPeerResult;
              })
              .get(txTimeout, SECONDS));
        } catch (Exception e) {
            LOG.error("failed to instantiate chaincode {}-{} with {}", name, version, params, e);
        }
        return empty();
    }

    public List<ChainCodeInfo> chaincodes() {
        try {
            return newArrayList(chaincodeRepo.findAll());
        } catch (Exception e) {
            LOG.error("failed to fetch chaincodes", e);
        }
        return emptyList();
    }

    public Optional<TxPeerResult> invoke(String name, String version, String... params) {
        ChainCodeID chaincode = ChainCodeID
          .newBuilder()
          .setName(name)
          .setVersion(version)
          .setPath(chaincodePath(name, version))
          .build();

        if (noPeersYet()) return empty();

        try {
            LOG.info("invoking chaincode {}-{} with: {}", name, version, params);
            return Optional.of(fabricSDK
              .invokeChaincode(chaincode, params)
              .thenApplyAsync(RESPONSE2TXPEERRESULT_FUNC)
              .get(txTimeout, SECONDS));
        } catch (Exception e) {
            LOG.error("failed to invoke chaincode {}-{} with {}", name, version, params, e);
        }
        return empty();
    }

    private static Function<ProposalResponse, TxPeerResult> RESPONSE2TXPEERRESULT_FUNC = proposalResponse -> nonNull(proposalResponse) ? new TxPeerResult(proposalResponse.getTransactionID(), proposalResponse
      .getPeer()
      .getName(), proposalResponse.getStatus() == SUCCESS ? 1 : 0) : null;

    private static Function<ProposalResponse, TxResult> RESPONSE2TXRESULT_FUNC = proposalResponse -> nonNull(proposalResponse) ? new TxResult<>(proposalResponse.getTransactionID(), proposalResponse
      .getPeer()
      .getName(), proposalResponse.getStatus() == SUCCESS ? 1 : 0) : null;

    public Optional<QueryResult> query(String name, String version, String... args) {
        ChainCodeID chaincode = ChainCodeID
          .newBuilder()
          .setName(name)
          .setVersion(version)
          .setPath(name)
          .build();

        if (noPeersYet()) return empty();

        try {
            LOG.info("querying chaincode {}-{} with {}", name, version, args);
            return Optional
              .ofNullable(fabricSDK.queryChaincode(chaincode, args))
              .map(QueryResult::new);
        } catch (Exception e) {
            LOG.error("failed to invoke chaincode {}-{} with {}", name, version, args, e);
        }
        return empty();
    }

    private boolean noPeersYet() {
        return fabricSDK
          .chainPeers()
          .isEmpty();
    }

}
