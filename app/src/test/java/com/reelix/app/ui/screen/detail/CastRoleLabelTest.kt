package com.reelix.app.ui.screen.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 演职员第二行「身份/角色」的归一化。
 *
 * 豆瓣对「演自己」的条目返回 character="自己"，真机上整行演职员都显示「自己」。
 * 这些值必须被识别成「没有角色信息」，由 UI 回退成「演员」。
 */
class CastRoleLabelTest {

    @Test
    fun `self roles from douban and tmdb are treated as no role`() {
        assertTrue("自己".isSelfReferential("迈克尔·杰克逊"))
        assertTrue("本人".isSelfReferential("某人"))
        assertTrue("Himself".isSelfReferential("Keanu Reeves"))
        assertTrue("Herself".isSelfReferential("Scarlett Johansson"))
        assertTrue("Self".isSelfReferential("Someone"))
    }

    @Test
    fun `role identical to the actor name is treated as no role`() {
        assertTrue("肯尼·奥特加".isSelfReferential("肯尼·奥特加"))
    }

    @Test
    fun `real character names are preserved`() {
        assertFalse("导演".isSelfReferential("肯尼·奥特加"))
        assertFalse("Neo".isSelfReferential("Keanu Reeves"))
        assertFalse("钢铁侠".isSelfReferential("小罗伯特·唐尼"))
    }
}
