package com.zongkong.core.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LedgerTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private val wechat = """
        微信支付账单明细,,,,,,,,,,
        微信昵称：[某人],,,,,,,,,,
        起始时间：[2026-10-16 00:00:00] 终止时间：[2026-10-16 23:59:59],,,,,,,,,,
        ----------------------微信支付账单明细列表--------------------,,,,,,,,,,
        交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号	,商户单号	,备注
        2026-10-16 12:31:05,商户消费,美团外卖,"午饭,黄焖鸡",支出,¥18.50,零钱,支付成功,4200001	,M1	,/
        2026-10-16 08:10:00,商户消费,北京地铁,乘车,支出,¥4.00,零钱,支付成功,4200002	,M2	,/
        2026-10-16 09:00:00,转账,某同学,/,收入,¥50.00,/,已存入零钱,4200003	,/	,/
        2026-10-16 20:00:00,商户消费,淘宝,衣服,支出,¥99.00,零钱,已全额退款,4200004	,M4	,/
    """.trimIndent()

    private val alipay = """
        支付宝交易记录明细查询
        账号:[xxx]
        交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注,
        2026/10/16 14:20,文化休闲,粉笔教育,fb***,行测课程,支出,199.00,余额宝,交易成功,2026101622001,T1,,
        2026/10/16 21:05,餐饮美食,瑞幸咖啡,lk***,生椰拿铁,支出,9.90,花呗,交易成功,2026101622002,T2,,
    """.trimIndent()

    @Test
    fun importsWechatAndAlipayBills() {
        val w = Ledger.parseBill(wechat, zone)
        assertNull(w.error)
        assertEquals("微信", w.source)
        assertEquals(listOf(1850L, 400L), w.items.map { it.cents })
        assertEquals("美团外卖 · 午饭,黄焖鸡", w.items[0].what)
        assertEquals("吃饭", w.items[0].cat)
        assertEquals("交通", w.items[1].cat)
        assertEquals("4200001", w.items[0].ref)
        assertEquals(2, w.skipped)

        val a = Ledger.parseBill(alipay, zone)
        assertEquals("支付宝", a.source)
        assertEquals(listOf(19900L, 990L), a.items.map { it.cents })
        assertEquals("学习", a.items[0].cat)

        var (book, n) = Ledger.add(LedgerBook(), w.items + a.items)
        assertEquals(4, n)
        // 再导一次：不重复
        val again = Ledger.add(book, w.items)
        assertEquals(0, again.second)
        book = again.first
        val today = Ledger.day(book, LocalDate.of(2026, 10, 16), zone)
        assertEquals(1850L + 400 + 19900 + 990, Ledger.total(today))
        assertEquals("学习", Ledger.byCat(today).first().first)
        assertTrue(Ledger.line(today), Ledger.line(today).startsWith("231.40 元（学习 199、吃饭 28.40、交通 4）"))
    }

    @Test
    fun amountsAndBadFiles() {
        assertEquals(1850L, Ledger.cents("¥18.5"))
        assertEquals(1800L, Ledger.cents("18元"))
        assertEquals(1L, Ledger.cents("0.005"))
        assertNull(Ledger.cents("十八"))
        assertEquals("18.50", Ledger.yuan(1850))
        assertEquals("18", Ledger.yuan(1800))
        assertNotNull(Ledger.parseBill("随便一段文字", zone).error)
    }
}
