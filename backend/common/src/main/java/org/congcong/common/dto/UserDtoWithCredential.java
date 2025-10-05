package org.congcong.common.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class UserDtoWithCredential extends UserDTO {

    private String credential;

}
