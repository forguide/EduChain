package top.zy68.personservice.service.impl;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.crypto.hash.Sha256Hash;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import top.zy68.fbChainService.dao.AchieveDao;
import top.zy68.fbChainService.dao.EduRecordDao;
import top.zy68.fbChainService.dao.PersonDao;
import top.zy68.fbChainService.dao.PersonUserDao;
import top.zy68.fbChainService.entity.EduRecord;
import top.zy68.fbChainService.entity.Person;
import top.zy68.fbChainService.entity.PersonUser;
import top.zy68.personservice.enums.enums.CodeMessageEnums;
import top.zy68.personservice.enums.enums.KeyStatus;
import top.zy68.personservice.exception.BaseException;
import top.zy68.personservice.exception.ClientBusinessException;
import top.zy68.personservice.exception.InternalBusinessException;
import top.zy68.personservice.pojo.KeyStoreInfo;
import top.zy68.personservice.pojo.UserInfoPo;
import top.zy68.personservice.pojo.UserParam;
import top.zy68.personservice.pojo.uservo.RspUserInfoVo;
import top.zy68.personservice.service.IKeyStoreService;
import top.zy68.personservice.service.UserService;
import top.zy68.personservice.config.AesUtils;
import top.zy68.personservice.utils.JwtUtil;
import top.zy68.personservice.utils.CommonUtils;
import top.zy68.personservice.vo.UserInfoVO;

import javax.annotation.Resource;
import java.util.*;

/**
 * @ClassName UserServiceImpl
 * @Description ?????????????????????
 * @Author Sustart
 * @Date2022/3/5 17:33
 * @Version 1.0
 **/
@Service("userService")
@Slf4j
public class UserServiceImpl implements UserService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    PersonUserDao personUserDao;
    @Resource
    PersonDao personDao;
    @Resource
    EduRecordDao eduRecordDao;
    @Resource
    AchieveDao achieveDao;

    @Resource
    IKeyStoreService keyStoreService;

    @Autowired
    private AesUtils aesUtils;

    @Qualifier(value = "cacheManager")
    private CacheManager cacheManager;

    /**
     * ????????????Token
     *
     * @param personId ????????????
     * @return token?????????
     */
    @Override
    public String generateAccountToken(String personId) {
        String salt = JwtUtil.generateSalt();
        // ???redis??????????????????????????????salt????????????????????????
        // ??????redisTokenKey???????????????????????????????????????redis????????????????????????
        String redisTokenKey = personId + "token";
        if (stringRedisTemplate.opsForValue().get(redisTokenKey) != null) {
            stringRedisTemplate.delete(redisTokenKey);
        }
        stringRedisTemplate.opsForValue().set(redisTokenKey, salt);
        // token??????????????????????????????????????????
        long activeTime = 5 * 24 * 60 * 60L;
        String token = JwtUtil.sign(personId, salt, activeTime);
        if (Objects.isNull(token)) {
            throw new InternalBusinessException("Token????????????");
        }
        return token;
    }

    /**
     * ??????personId?????????token?????????salt
     *
     * @param personId ????????????
     * @return salt
     */
    @Override
    public String getTokenSaltByPersonId(String personId) {
        String redisTokenKey = personId + "token";
        String tokenSalt = stringRedisTemplate.opsForValue().get(redisTokenKey);
        if (Objects.isNull(tokenSalt)) {
            throw new ClientBusinessException("?????????????????????salt");
        }
        return tokenSalt;
    }

    /**
     * ????????????????????????????????????salt???
     *
     * @param personId ????????????
     */
    @Override
    public void deleteLoginInfo(String personId) {
        // redis????????????token????????????salt
        String redisTokenKey = personId + "token";
        stringRedisTemplate.delete(redisTokenKey);
    }

    /**
     * ?????????????????????????????????
     *
     * @param personId ????????????
     * @param phone    ???????????????
     */
    @Override
    public void updateUserPhone(String personId, String phone) {
        PersonUser personUser = personUserDao.queryPersonUserByPersonId(personId);
        if (personUser == null) {
            throw new ClientBusinessException("??????????????????");
        }
        personUser.setPhone(phone);
        try {
            personUserDao.updatePersonUserByPersonId(personUser);
        } catch (Exception e) {
            e.printStackTrace();
            throw new ClientBusinessException("????????????????????????");
        }
        log.info("??????????????????????????????.");
    }

    /**
     * ???????????????????????????
     *
     * @param personId ????????????
     * @param email    ?????????
     * @param captcha  ?????????
     */
    @Transactional(rollbackFor = RuntimeException.class)
    @Override
    public void updateUserMail(String personId, String email, String captcha) {
        PersonUser personUser = personUserDao.queryPersonUserByPersonId(personId);
        if (personUser == null) {
            throw new ClientBusinessException("??????????????????");
        }
        String cacheCaptcha = stringRedisTemplate.opsForValue().get(email);
        if (Objects.isNull(cacheCaptcha)) {
            throw new ClientBusinessException("??????????????????");
        }
        if (!Objects.equals(cacheCaptcha, captcha)) {
            throw new ClientBusinessException("???????????????");
        }

        stringRedisTemplate.delete(email);
        personUser.setEmail(email);

        try {
            personUserDao.updatePersonUserByPersonId(personUser);
        } catch (Exception e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.warn("??????????????????" + e.getMessage());
            throw new ClientBusinessException("??????????????????.");
        }
    }

    /**
     * ??????????????????
     *
     * @param personId  ????????????
     * @param originPwd ?????????
     * @param newPwd    ?????????
     */
    @Transactional(rollbackFor = RuntimeException.class)
    @Override
    public String updateUserPwd(String personId, String originPwd, String newPwd, JSONObject oldPemFile) {
        PersonUser personUser = personUserDao.queryPersonUserByPersonId(personId);
        if (personUser == null) {
            throw new ClientBusinessException("??????????????????");
        }

        // ???????????????????????????
        String originEncryptPwd = new Sha256Hash(originPwd, personUser.getSalt()).toHex();
        if (!Objects.equals(personUser.getPassword(), originEncryptPwd)) {
            log.info("???????????????????????????????????????.");
            throw new ClientBusinessException("?????????????????????????????????.");
        }
        String aPrivate = oldPemFile.getString("privateKey");
        String address = oldPemFile.getString("address");
        if(!Objects.equals(address,personUser.getAddress())){
            log.info("???????????????");
            throw new ClientBusinessException("?????????????????????????????????");
        }


        // ???????????????
        String salt = JwtUtil.generateSalt();
        String newEncryptPwd = new Sha256Hash(newPwd, salt).toHex();
        personUser.setPassword(newEncryptPwd);
        personUser.setSalt(salt);
        String newPri = "";
        try {
            personUserDao.updatePersonUserByPersonId(personUser);
            originPwd  = AesUtils.handPwdLength(originPwd);
            String oldPri = aesUtils.aesDecrypt(aPrivate, originPwd, "");
            newPwd =  AesUtils.handPwdLength(newPwd);
            newPri =  aesUtils.aesEncrypt(oldPri, newPwd, "");
        } catch (Exception e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.warn("??????????????????" + e.getMessage());
            throw new ClientBusinessException("????????????");
        }
        log.info("????????????????????????.");
        return newPri;
    }

    /**
     * ??????????????????
     *
     * @param mail    ??????
     * @param newPwd  ?????????
     * @param captcha ?????????
     */
    @Override
    public void resetUserPwd(String mail, String newPwd, String captcha) {
        PersonUser personUser = personUserDao.queryPersonUserByEmail(mail);
        if (personUser == null) {
            throw new ClientBusinessException("??????????????????");
        }
        String cacheCaptcha = stringRedisTemplate.opsForValue().get(mail);
        if (cacheCaptcha == null || !Objects.equals(cacheCaptcha, captcha)) {
            throw new ClientBusinessException("??????????????????????????????.");
        }
        // ???????????????
        String salt = JwtUtil.generateSalt();
        String newEncryptPwd = new Sha256Hash(newPwd, salt).toHex();
        personUser.setPassword(newEncryptPwd);
        personUser.setSalt(salt);
        stringRedisTemplate.delete(mail);
        int row = personUserDao.updatePersonUserByPersonId(personUser);
        if (row != 1) {
            log.info("????????????????????????.");
            throw new ClientBusinessException("????????????");
        }
        log.info("????????????????????????.");
    }

    /**
     * ???????????????????????????
     *
     * @param personId ????????????
     * @return ??????????????????????????????
     */
    @Override
    public UserInfoVO getUserInfo(String personId) {
        Person person = personDao.queryPersonByPersonId(personId);
        PersonUser personUser = personUserDao.queryPersonUserByPersonId(personId);
        if (person == null || personUser == null) {
            throw new ClientBusinessException("??????????????????");
        }
        return new UserInfoVO()
                .setPersonId(person.getPersonId())
                .setName(person.getName())
                .setGender(person.getGender())
                .setNation(person.getNation())
                .setBirthday(person.getBirthday())
                .setEmail(personUser.getEmail())
                .setPhone(personUser.getPhone())
                .setAddress(person.getAddress());
    }

    /**
     * ??????personId??????????????????
     *
     * @param personId ????????????
     * @return ????????????
     */
    @Override
    public PersonUser queryUserByPersonId(String personId) {
        return personUserDao.queryPersonUserByPersonId(personId);
    }

    /**
     * ??????mail??????????????????
     *
     * @param mail ????????????
     * @return ????????????
     */
    @Override
    public PersonUser queryUserByMail(String mail) {
        return personUserDao.queryPersonUserByEmail(mail);
    }

    /**
     * ??????????????????????????????????????????????????????
     *
     * @param personId ????????????
     * @return ????????????
     */
    @Override
    public Map<String, Integer> getUserData(String personId) {
        Map<String, Integer> resMap = new HashMap<>(2);
        List<EduRecord> eduRecords = eduRecordDao.queryAllByPersonId(personId);

        int achieveNum = 0;
        for (EduRecord eduRecord : eduRecords) {
            achieveNum += achieveDao.countEduAchieve(eduRecord.getEduId());
        }

        resMap.put("education", eduRecords.size());
        resMap.put("achieve", achieveNum);

        return resMap;
    }

    /**
     * add user by encrypt type
     */
    @Override
    public UserInfoPo newUser(String signUserId, int encryptType,
                                 String privateKeyEncoded,String password) throws Exception {
        log.info("start addUser signUserId:{},appId:{},encryptType:{}", signUserId,
                encryptType);
        // check user uuid exist
        PersonUser checkSignUserIdExists =personUserDao.findUserBySignUserId(signUserId);
        if (Objects.nonNull(checkSignUserIdExists)) {

            throw new BaseException(CodeMessageEnums.USER_EXISTS);

        }

        // get keyStoreInfo
        KeyStoreInfo keyStoreInfo;
        if (StringUtils.isNotBlank(privateKeyEncoded)) {
            String privateKey;
            // decode base64 as raw private key
            try {
                privateKey = new String(CommonUtils.base64Decode(privateKeyEncoded));
                keyStoreInfo = keyStoreService.getKeyStoreFromPrivateKey(privateKey, encryptType,password);
            } catch (Exception ex) {
                log.error("newUser privatekey encoded format error???{}", privateKeyEncoded);
                throw new BaseException(CodeMessageEnums.PRIVATE_KEY_DECODE_FAIL);
            }
        } else {
            keyStoreInfo = keyStoreService.newKeyByType(encryptType,password);
        }

        // save user.
        UserInfoPo userInfoPo = new UserInfoPo();
        BeanUtils.copyProperties(keyStoreInfo, userInfoPo);
        userInfoPo.setEncryptType(encryptType);
        userInfoPo.setSignUserId(signUserId);
        //userInfoPo.setAppId(appId);
        log.info("end escda");
        return userInfoPo;
    }


    /**
     * save user.
     */
    public RspUserInfoVo saveUser(UserInfoPo userInfoPo) {
        // save user
        //userDao.insertUserInfo(userInfoPo);

        // return
        RspUserInfoVo rspUserInfoVo = new RspUserInfoVo();
        BeanUtils.copyProperties(userInfoPo, rspUserInfoVo);

        return rspUserInfoVo;
    }

    /**
     * query user by userId.
     */
    @Cacheable(cacheNames = "user")
    public PersonUser findBySignUserId(String signUserId) throws BaseException {
        log.info("start findBySignUserId. signUserId:{}", signUserId);
        PersonUser user=null; // userDao.findUserBySignUserId(signUserId);
        if (Objects.isNull(user) ) {
            log.warn("fail findBySignUserId, user not exists. userId:{}", signUserId);
            throw new BaseException(CodeMessageEnums.USER_NOT_EXISTS);
        }
        Optional.ofNullable(user)
                .ifPresent(u -> u.setPrivateKey(aesUtils.aesDecrypt(u.getPrivateKey())));
        log.info("end findBySignUserId. userId:{}", signUserId);
        return user;
    }

    public PersonUser findByAddress(String address) throws BaseException {
        log.info("start findUserByAddress. address:{}", address);
        PersonUser user =null; // userDao.findUserByAddress(address);
        if (Objects.isNull(user)) {
            log.warn("fail findUserByAddress, user not exists. address:{}", address);
            throw new BaseException(CodeMessageEnums.USER_NOT_EXISTS);
        }
        Optional.ofNullable(user)
                .ifPresent(u -> u.setPrivateKey(aesUtils.aesDecrypt(u.getPrivateKey())));
        log.info("end findUserByAddress. address:{}", address);
        return user;
    }

    /**
     * count of user.
     */
    public int countOfUser(UserParam param) {
        Integer count =null; //userDao.countOfUser(param);
        return count == null ? 0 : count;
    }

    /**
     * query user list.
     *
     * @param param encryptType 1: guomi, 0: standard
     */
    public List<RspUserInfoVo> findUserList(UserParam param) {
        log.info("start findUserList.");
        List<UserInfoPo> users =null; // userDao.findUserList(param);
        List<RspUserInfoVo> rspUserInfoVos = new ArrayList<>();
        for (UserInfoPo user : users) {
            RspUserInfoVo rspUserInfoVo = new RspUserInfoVo();
            BeanUtils.copyProperties(user, rspUserInfoVo);
            rspUserInfoVo.setPrivateKey(aesUtils.aesDecrypt(user.getPrivateKey()));
            rspUserInfoVos.add(rspUserInfoVo);
        }
        return rspUserInfoVos;
    }

    public List<RspUserInfoVo> findUserListByAppId(UserParam param) {
        log.info("start findUserListByAppId.");
        List<UserInfoPo> users =null; // userDao.findUserListByAppId(param);
        List<RspUserInfoVo> rspUserInfoVos = new ArrayList<>();
        for (UserInfoPo user : users) {
            RspUserInfoVo rspUserInfoVo = new RspUserInfoVo();
            BeanUtils.copyProperties(user, rspUserInfoVo);
            rspUserInfoVos.add(rspUserInfoVo);
        }
        return rspUserInfoVos;
    }



    /**
     * delete user by signUserId
     */
    @CacheEvict(cacheNames = "user", beforeInvocation = true)
    public void deleteBySignUserId(String signUserId) throws BaseException {
        log.info("start deleteByUuid signUserId:{}", signUserId);
        UserInfoPo user =null; // userDao.findUserBySignUserId(signUserId);
        if (Objects.isNull(user) || user.getStatus().equals(KeyStatus.SUSPENDED.getValue())) {
            log.warn("fail deleteByUuid, user not exists. signUserId:{}", signUserId);
            throw new BaseException(CodeMessageEnums.USER_NOT_EXISTS);
        }
        //userDao.deleteUserBySignUserId(signUserId);
        log.info("end deleteByUuid.");
    }


    public Boolean deleteAllUserCache() {
        log.info("delete all user cache");

        Cache cache = cacheManager.getCache("user");
        if (cache != null) {
            cache.clear();
        }
        return true;
    }

    public Boolean deleteAllCredentialCache() {
        log.info("delete all Credential cache");

        Cache cache = cacheManager.getCache("getCredentials");
        if (cache != null) {
            cache.clear();
        }
        return true;
    }

    public UserInfoPo findLatestUpdatedUser() {
        UserInfoPo user =null; // userDao.findLatestUpdateUser();
        return user;
    }

}
