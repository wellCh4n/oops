﻿export default [
  {
    path: '/user',
    layout: false,
    routes: [
      {
        name: 'login',
        path: '/user/login',
        component: './user/Login',
      },
      {
        component: './404',
      },
    ],
  },
  {
    path: '/welcome',
    name: 'welcome',
    icon: 'smile',
    component: './Welcome',
  },
  {
    path: '/application',
    name: 'application',
    icon: 'AppstoreOutlined',
    component: './Application'
  },
  {
    path: '/application/edit/:id',
    name: 'application-edit',
    icon: 'appstoreAdd',
    hideInMenu: true,
    component: './Application/Edit'
  },
  {
    path: '/application/add',
    name: 'application-add',
    icon: 'appstoreAdd',
    hideInMenu: true,
    component: './Application/Edit'
  },
  {
    path: '/admin',
    name: 'admin',
    icon: 'crown',
    access: 'canAdmin',
    routes: [
      {
        path: '/admin/sub-page',
        name: 'sub-page',
        icon: 'smile',
        component: './Welcome',
      },
      {
        component: './404',
      },
    ],
  },
  {
    name: 'list.table-list',
    icon: 'table',
    path: '/list',
    component: './TableList',
  },
  {
    path: '/',
    redirect: '/application',
  },
  {
    component: './404',
  },
];
