package com.global.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * View Model object for storing the user's key and password.
 */

@Getter
@Setter
public class KeyAndPasswordVM {

	private String key;

	private String newPassword;

}