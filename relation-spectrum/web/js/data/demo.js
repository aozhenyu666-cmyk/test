// Demo data so the app can be explored before entering real people.
// Dates are relative to today so the alerts always look current.

import * as db from '../store/db.js';
import { newEntity, newInteraction, todayISO } from '../core/models.js';

const daysAgo = (n) => todayISO(new Date(Date.now() - n * 86400000));
const mmdd = (inDays) => {
  const d = new Date(Date.now() + inDays * 86400000);
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

export async function loadDemoData() {
  const mom = newEntity({
    name: '妈妈（示例）', tier: 'kin', role: '母亲', birthYear: 1970, cadenceDays: 7,
    keyDates: [{ label: '妈妈生日', date: mmdd(9), lunar: false }],
    needs: '希望我身体好、按时吃饭，偶尔打电话说说近况',
    redLines: '不喜欢我熬夜，也不喜欢被说“你不懂”',
  });
  const dad = newEntity({
    name: '爸爸（示例）', tier: 'kin', role: '父亲', birthYear: 1967, cadenceDays: 7,
    keyDates: [{ label: '爸爸生日', date: mmdd(52), lunar: true }],
    needs: '嘴上不说，但很在意我有没有出息、过得好不好',
  });
  const friend = newEntity({
    name: '阿哲（示例）', tier: 'bond', role: '大学最好的朋友', cadenceDays: 21,
    needs: '正在考研，压力很大，需要被理解而不是被催',
    redLines: '被追问“你到底在忙什么”',
  });
  const boss = newEntity({
    name: '现在的公司（示例）', tier: 'deal', kind: 'org', role: '雇主', cadenceDays: 7,
    contract: {
      myChips: '负责核心模块，熟悉业务；有猎头联系过', theirChips: '工资、推荐信、项目经验',
      batna: '猎头介绍的岗位，薪资高 30%', bottomLine: '年底前必须调薪，否则开始面试', hourlyValue: 60,
    },
  });

  const its = [
    // Parents: last proactive call to mom was 12 days ago; dad gets calls from mom's phone.
    newInteraction({ entityId: mom.id, date: daysAgo(12), type: 'care', title: '给妈妈打电话', give: { minutes: 15, emotion: 2 }, get: { emotion: 3 }, feeling: 1 }),
    newInteraction({ entityId: mom.id, date: daysAgo(30), type: 'help', initiator: 'them', title: '妈妈寄来一箱吃的', get: { money: 300, emotion: 3 }, feeling: 2 }),
    newInteraction({ entityId: mom.id, date: daysAgo(120), type: 'meet', title: '国庆回家', give: { minutes: 2880, emotion: 3 }, get: { minutes: 2880, emotion: 4, money: 500 }, feeling: 2 }),
    newInteraction({ entityId: dad.id, date: daysAgo(120), type: 'meet', title: '国庆回家', give: { minutes: 2880, emotion: 2 }, get: { minutes: 2880, emotion: 3 }, feeling: 1 }),
    newInteraction({ entityId: dad.id, date: daysAgo(40), type: 'chat', initiator: 'them', title: '爸爸问工作情况', get: { minutes: 10, emotion: 2 } }),

    // Friend: heavy support, then a run of postponed invites.
    newInteraction({ entityId: friend.id, date: daysAgo(70), type: 'support', initiator: 'them', title: '陪 TA 聊考研焦虑', give: { minutes: 70, emotion: 4 }, get: { emotion: 1 }, feeling: 1 }),
    newInteraction({ entityId: friend.id, date: daysAgo(55), type: 'gift', title: '寄了一盒咖啡给 TA 提神', give: { money: 120, emotion: 2 }, get: { emotion: 1 } }),
    newInteraction({ entityId: friend.id, date: daysAgo(30), type: 'invite', title: '约去 TA 学校附近玩，TA 说临时有事', invite: { by: 'me', status: 'flaked' }, give: { minutes: 120, money: 60 }, feeling: -1 }),
    newInteraction({ entityId: friend.id, date: daysAgo(18), type: 'invite', title: '约周末吃饭，TA 说最近忙', invite: { by: 'me', status: 'postponed' }, feeling: -1 }),
    newInteraction({ entityId: friend.id, date: daysAgo(6), type: 'invite', title: '又约了一次，TA 说“再说吧”', invite: { by: 'me', status: 'postponed' }, feeling: -2 }),

    // Company: overtime, unkept promise.
    newInteraction({ entityId: boss.id, date: daysAgo(200), type: 'promise', initiator: 'them', title: '老板说年中调薪', promise: { by: 'them', status: 'broken', content: '年中调薪 15%' }, feeling: -1 }),
    newInteraction({ entityId: boss.id, date: daysAgo(60), type: 'promise', initiator: 'them', title: '老板说上市后给期权', promise: { by: 'them', status: 'pending', content: '上市后给期权' } }),
    newInteraction({ entityId: boss.id, date: daysAgo(90), type: 'promise', initiator: 'them', title: '说好的项目奖金没发', promise: { by: 'them', status: 'broken', content: '项目奖金' }, feeling: -2 }),
    newInteraction({ entityId: boss.id, date: daysAgo(10), type: 'work', title: '本周加班', give: { minutes: 900, emotion: 2 }, get: { money: 0 }, feeling: -1 }),
    newInteraction({ entityId: boss.id, date: daysAgo(3), type: 'work', title: '月薪', give: { minutes: 9600 }, get: { money: 9000 } }),
  ];

  await db.putMany('entities', [mom, dad, friend, boss]);
  await db.putMany('interactions', its);
}
