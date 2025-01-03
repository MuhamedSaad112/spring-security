package com.global.controller;

import java.util.Optional;


import com.global.dto.KeyAndPasswordVM;
import com.global.dto.ManagedUserVM;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.thymeleaf.util.StringUtils;

import com.global.entity.User;
import com.global.errors.EmailAlreadyUsedException;
import com.global.errors.InvalidPasswordException;
import com.global.errors.LoginAlreadyUsedException;
import com.global.repository.UserRepository;
import com.global.security.SecurityUtils;
import com.global.dto.AdminUserDTO;
import com.global.service.MailService;
import com.global.dto.PasswordChangeDTO;
import com.global.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Log4j2
public class AccountController {

	private final UserRepository userRepository;

	private final UserService userService;

	private final MailService mailService;

	private static class AccountControllerException extends RuntimeException {

		private AccountControllerException(String message) {
			super(message);
		}
	}

	/**
	 * {@code POST  /register} : register the user.
	 *
	 * @param managedUserVM the managed user View Model.
	 * @throws InvalidPasswordException  {@code 400 (Bad Request)} if the password
	 *                                   is incorrect.
	 * @throws EmailAlreadyUsedException {@code 400 (Bad Request)} if the email is
	 *                                   already used.
	 * @throws LoginAlreadyUsedException {@code 400 (Bad Request)} if the login is
	 *                                   already used.
	 */

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public void registerAccount(@Valid @RequestBody ManagedUserVM managedUserVM) {

		if (isPasswordLengthInvalid(managedUserVM.getPassword())) {
			throw new InvalidPasswordException();
		}

		User user = userService.registerUser(managedUserVM, managedUserVM.getPassword());
		mailService.sendActivationEmail(user);
	}

	/**
	 * {@code GET  /activate} : activate the registered user.
	 *
	 * @param key the activation key.
	 * @throws RuntimeException {@code 500 (Internal Server Error)} if the user
	 *                          couldn't be activated.
	 */

	@GetMapping("/activate")
	public void activateAccount(@RequestParam(value = "key") String key) {

		Optional<User> user = userService.activateRegistration(key);

		if (!user.isPresent()) {
			throw new AccountControllerException("No user was found for this activation key");
		} else {

			mailService.sendCreationEmail(user.get());
		}

	}

	/**
	 * {@code GET  /authenticate} : check if the user is authenticated, and return
	 * its login.
	 *
	 * @param request the HTTP request.
	 * @return the login if the user is authenticated.
	 */

	@GetMapping("/authenticate")
	public String isAuthenticated(HttpServletRequest request) {
		log.debug("REST request to check if the current user is authenticated");

		return request.getRemoteUser();
	}

	/**
	 * {@code GET  /account} : get the current user.
	 *
	 * @return the current user.
	 * @throws RuntimeException {@code 500 (Internal Server Error)} if the user
	 *                          couldn't be returned.
	 */

	@GetMapping("/account")
	public AdminUserDTO getAccount() {
		return userService.getUserWithRoles().map(AdminUserDTO::new)
				.orElseThrow(() -> new AccountControllerException("User could not be found"));
	}

	/**
	 * {@code POST  /account} : update the current user information.
	 *
	 * @param userDTO the current user information.
	 * @throws EmailAlreadyUsedException {@code 400 (Bad Request)} if the email is
	 *                                   already used.
	 * @throws RuntimeException          {@code 500 (Internal Server Error)} if the
	 *                                   user login wasn't found.
	 */

	@PutMapping("/account")
	public void updateAccount(@RequestBody @Valid AdminUserDTO userDTO) {

		String userLogin = SecurityUtils.getCurrentUserLogin()
				.orElseThrow(() -> new AccountControllerException("Current user login not found"));

		Optional<User> existingUser = userRepository.findOneByEmailIgnoreCase(userDTO.getEmail());

		if (existingUser.isPresent() && (!existingUser.get().getLogin().equalsIgnoreCase(userLogin))) {
			throw new EmailAlreadyUsedException();
		}

		Optional<User> user = userRepository.findOneByLogin(userLogin);

		if (!user.isPresent()) {
			throw new AccountControllerException("User could not be found");
		}

		userService.updateUser(userDTO.getFirstName(), userDTO.getLastName(), userDTO.getEmail(), userDTO.getLangKey(),
				userDTO.getImageUrl());

	}

	/**
	 * {@code POST  /account/change-password} : changes the current user's password.
	 *
	 * @param passwordChangeDTO current and new password.
	 * @throws InvalidPasswordException {@code 400 (Bad Request)} if the new
	 *                                  password is incorrect.
	 */

	@PostMapping(path = "/account/change-password")
	public void changePassword(@RequestBody PasswordChangeDTO passwordChangeDTO) {

		if (isPasswordLengthInvalid(passwordChangeDTO.getNewPassword())) {
			throw new InvalidPasswordException();
		}
		userService.changePassword(passwordChangeDTO.getCurrentPassword(), passwordChangeDTO.getNewPassword());
	}

	/**
	 * {@code POST   /account/reset-password/init} : Send an email to reset the
	 * password of the user.
	 *
	 * @param mail the mail of the user.
	 */

	@PostMapping(path = "/account/reset-password/init")
	public void requsetPasswordRest(@RequestBody String mail) {

		Optional<User> user = userService.requestPasswordReset(mail);

		if (user.isPresent()) {
			mailService.sendPasswordResetEmail(user.get());
		} else {

			// Pretend the request has been successful to prevent checking which emails
			// really exist
			// but log that an invalid attempt has been made
			log.warn("Password reset requested for non existing mail");

		}
	}

	/**
	 * {@code POST   /account/reset-password/finish} : Finish to reset the password
	 * of the user.
	 *
	 * @param keyAndPasswordVM the generated key and the new password.
	 * @throws InvalidPasswordException {@code 400 (Bad Request)} if the password is
	 *                                  incorrect.
	 * @throws RuntimeException         {@code 500 (Internal Server Error)} if the
	 *                                  password could not be reset.
	 */

	@PostMapping(path = "/account/reset-password/finish")
	public void finshPasswordRest(@RequestBody KeyAndPasswordVM keyAndPasswordVM) {

		if (isPasswordLengthInvalid(keyAndPasswordVM.getNewPassword())) {
			throw new InvalidPasswordException();
		}

		Optional<User> user = userService.completePasswordReset(keyAndPasswordVM.getNewPassword(),
				keyAndPasswordVM.getKey());
		if (!user.isPresent()) {
			throw new AccountControllerException("No user was found for this reset key");
		}

	}

	private static boolean isPasswordLengthInvalid(String password) {

		return (StringUtils.isEmpty(password) || password.length() > ManagedUserVM.PASSWORD_MAX_LENGTH
				|| password.length() < ManagedUserVM.PASSWORD_MIN_LENGTH);
	}
}
