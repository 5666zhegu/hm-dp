package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.MessageConstant;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.message.Message;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * 发送验证码
     *
     * @param phone 手机号
     * @return 发送结果
     */
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //2.生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3.保存验证码到session
        session.setAttribute(MessageConstant.CODE, code);
        //4.发送验证码
        log.debug("发送验证码成功，验证码：{}", code);

        return Result.ok();
    }

    /**
     * 一键登陆注册功能
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }
        //2.校验验证码
        String code = loginForm.getCode();
        String cacheCode = session.getAttribute(MessageConstant.CODE).toString();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }
        //3.是否存在用户
        String phone = loginForm.getPhone();
        User user = query().eq("phone", phone).one();
        //4.不存在，创建用户
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        //5.保存用户到session
        session.setAttribute(MessageConstant.USER, user);
        return Result.ok();
    }


    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + "大天才");
        save(user);
        return user;
    }
}
