import { definePlugin, type UiControl, type UiPage } from '../../src/index.js';
import catalog from '../catalog/index.js';

export default definePlugin({
  ...catalog,
  async uiPage(request, host): Promise<UiPage> {
    if (request.page === 'details') {
      return { title: '资源详情', template: 'detail', controls: [
        { id: 'scope', type: 'status', label: '资源', value: request.scope?.id ?? request.entry },
        { id: 'qr', type: 'qr', label: '示例二维码', value: 'MeloX UI Demo' },
      ] };
    }
    const search = request.entry === 'search';
    const collection = ['home', 'explore', 'library'].includes(request.entry);
    if (search || collection) {
      const result = await catalog.search(request.query || 'tone', request.index, 10);
      return { title: '示例曲库', template: search ? 'search' : 'collection', controls: [], tracks: result.items, hasMore: result.hasMore };
    }
    const controls: UiControl[] = [
      { id: 'active', type: 'toggle', label: '示例开关', value: await host.storage.get('ui.active') === 'true' },
      { id: 'choice', type: 'singleChoice', label: '模式', value: await host.storage.get('ui.choice') ?? 'a', options: [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }] },
      { id: 'multi', type: 'multiChoice', label: '筛选项', value: JSON.parse(await host.storage.get('ui.multi') ?? '[]'), options: [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }] },
      { id: 'level', type: 'slider', label: '级别', min: 0, max: 100, value: Number(await host.storage.get('ui.level') ?? 50) },
      { id: 'limit', type: 'number', label: '数量', value: Number(await host.storage.get('ui.limit') ?? 10) },
      { id: 'name', type: 'text', label: '名称', value: await host.storage.get('ui.name') ?? '' },
      { id: 'server', type: 'url', label: '示例地址', value: await host.storage.get('ui.server') ?? 'https://example.org' },
      { id: 'secret', type: 'secret', label: '示例密码（请使用虚构内容）' },
      { id: 'submit', type: 'button', label: '运行示例动作' },
      { id: 'details', type: 'link', label: '打开详情', target: 'details' },
      { id: 'status', type: 'status', label: '当前入口', value: request.entry },
    ];
    return { title: '界面扩展示例', template: request.entry === 'services' ? 'connection' : request.entry === 'playerPage' ? 'status' : 'settings', controls };
  },
  async uiAction(request, host) {
    if (!['secret', 'submit'].includes(request.control) && request.value !== undefined) {
      await host.storage.set(`ui.${request.control}`, typeof request.value === 'string' ? request.value : JSON.stringify(request.value));
    }
    return { message: '示例操作完成' };
  },
});
